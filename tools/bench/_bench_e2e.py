#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""端到端工具调用压测：**真的把消息发给应用**，让它跑完整 Agent 循环。

和 _bench_toolcalls.py 的区别（两个都要看，量的是不同的东西）：
  · _bench_toolcalls.py  → 只发一次请求、直接看模型第一次吐什么。量的是"模型首次选对率"，
                           快（30 条 4 分钟），适合调提示词。
  · 本脚本               → 走真实链路：POST /api/chat → AgentLoop 循环 → 真工具执行 →
                           多轮自愈。量的是**用户实际感受到的**东西：一轮任务里工具调用
                           有没有失败、选错了能不能自己改对、要几轮、花多久。

指标定义（都写清楚，别糊）：
  合法率     第一次工具调用"解析得出来 + 工具名存在 + 必需参数齐全"的比例。
             —— 这是 harness 能负责的部分，必须接近 100%。
  首次选对率 第一次工具调用就是期望工具的比例。—— 这是模型+提示词的能力上限。
  最终选对率 整轮里出现过期望工具的比例（选错了靠看工具结果自己改对也算）。
  零错误率   整轮里**没有任何**工具调用报错（未找到工具/缺参数/执行失败）的比例。
             —— 用户不会看到一屏 ❌ 的比例。

用法：
    python tools/bench/_bench_e2e.py                 # 全部 30 条
    python tools/bench/_bench_e2e.py --only 2,3,6    # 指定几条
    python tools/bench/_bench_e2e.py --timeout 600
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _bench_toolcalls import TASKS, normalize  # noqa: E402  同一套任务集，两个基准可比
from _app import start_app, stop_app, kill_port  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
LLAMA = 'http://127.0.0.1:8788/v1'
APP_PORT = 8919
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_libe2e')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


# ---------------------------------------------------------------- HTTP 小工具
def req(url, data=None, timeout=60, method=None):
    body = None if data is None else json.dumps(data).encode('utf-8')
    r = urllib.request.Request(url, data=body, method=method,
                               headers={'Content-Type': 'application/json'} if body else {})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'replace'))


def real_java():
    home = os.environ.get('JAVA_HOME')
    if home and os.path.isfile(os.path.join(home, 'bin', 'java.exe')):
        return os.path.join(home, 'bin', 'java.exe')
    return shutil.which('java') or 'java'


def kill_port(port):
    out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)
    time.sleep(1)


def wait_port(port, seconds=120):
    for _ in range(seconds):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            return True
        except Exception:
            time.sleep(1)
    return False


# ---------------------------------------------------------------- 工具清单（从真实提示词里抠）
TOOL_LINE = re.compile(r'^- ([a-z0-9_]+)(?:\(([^)]*)\))?:', re.M)


def load_tool_spec():
    """返回 {工具名: set(必需参数)}，来源是模型真正看到的那份提示词，不另写一份。"""
    pv = req(APP + '/api/runtime/prompt-preview?mode=standard&message=x&full=true', timeout=60)
    prompt = (pv.get('data') or {}).get('systemPrompt') or ''
    spec = {}
    for m in TOOL_LINE.finditer(prompt):
        required = {a.strip().rstrip('*') for a in (m.group(2) or '').split(',')
                    if a.strip().endswith('*')}
        spec[m.group(1)] = required
    return spec


# ---------------------------------------------------------------- 解析模型输出里的调用
FUNCTION = re.compile(r'<function\s*=\s*([A-Za-z_][\w.\-]*)\s*>(.*?)</function>', re.S)
PARAMETER = re.compile(r'<parameter\s*=\s*([A-Za-z_][\w.\-]*)\s*>(.*?)</parameter>', re.S)


def parse_first_call(text):
    m = FUNCTION.search(text or '')
    if not m:
        return None, {}
    args = {pm.group(1).strip(): pm.group(2).strip() for pm in PARAMETER.finditer(m.group(2))}
    return m.group(1).strip(), args


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only', default='')
    ap.add_argument('--timeout', type=int, default=900, help='单条任务最多等多久（秒）')
    ap.add_argument('--out', default=os.path.join(TMP, 'result.json'))
    args = ap.parse_args()

    only = [int(x) for x in args.only.split(',') if x.strip()]
    tasks = [t for t in TASKS if not only or t['id'] in only]

    if not os.path.isfile(JAR):
        raise SystemExit('没有 jar：%s（先 python tools/dev/_mvn.py -o -DskipTests package）' % JAR)
    try:
        req(LLAMA + '/models', timeout=5)
    except Exception as e:
        raise SystemExit('本地模型服务 %s 不通（%s），先把 llama-server 起起来' % (LLAMA, e))

    kill_port(APP_PORT)
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
    os.makedirs(WS, exist_ok=True)
    for name, content in (('a.txt', 'hello\nhello\nl3\nl4\nl5\nl6\n'),
                          ('config.txt', 'port=8080\nhost=127.0.0.1\nmode=local\n'),
                          ('old.txt', 'x\n')):
        with open(os.path.join(WS, name), 'w', encoding='utf-8') as f:
            f.write(content)
    os.makedirs(os.path.join(WS, 'tmp_out'), exist_ok=True)
    os.makedirs(os.path.join(WS, 'src'), exist_ok=True)
    for i in range(3):
        with open(os.path.join(WS, 'src', 'F%d.java' % i), 'w', encoding='utf-8') as f:
            f.write('// TODO: 待办 %d\npublic class F%d {}\n' % (i, i))

    # 直接用 custom provider 指到用户那个 llama-server：不让应用自己再去拉一个模型进程
    with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
        json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
                   'baseUrl': LLAMA, 'apiKey': 'sk-local', 'model': 'lion-models1',
                   'toolCallMode': 'text'}, f, ensure_ascii=False)

    log = open(LOG, 'w', encoding='utf-8', errors='replace')
    proc, log = start_app(APP_PORT, HOME, log_path=LOG,
                          extra_args=['--lionbox.agent.tool-timeout-seconds=90'])
    try:
        if not wait_port(APP_PORT):
            raise SystemExit('应用起不来，看日志：%s' % LOG)

        spec = load_tool_spec()
        print('模型可见工具 %d 个' % len(spec))
        wresp = req(APP + '/api/workspaces', {'path': WS})
        ws = wresp.get('data') or {}
        ws_id = ws.get('id') or ws.get('workspaceId') or ws.get('path')
        if not ws_id:
            raise SystemExit('注册工作区失败：%s' % json.dumps(wresp, ensure_ascii=False)[:200])

        rows = []
        print('=' * 96)
        print('端到端工具调用压测：%d 条复杂任务（真模型 %s，真 Agent 循环，真工具执行）' % (len(tasks), LLAMA))
        print('=' * 96)

        for t in tasks:
            sresp = req(APP + '/api/sessions', {'workspaceId': ws_id, 'mode': 'standard'})
            if not sresp.get('success', True):
                raise SystemExit('创建会话失败：%s' % json.dumps(sresp, ensure_ascii=False)[:200])
            sdata = sresp.get('data')
            # 会话 DTO 的字段是 sessionId（不是 id）—— 第一版按 id 取，取到 None，
            # 结果 30 条全部 500，白白跑了一轮
            sid = sdata if isinstance(sdata, str) else (sdata or {}).get('sessionId') or (sdata or {}).get('id')
            if not sid:
                raise SystemExit('创建会话返回里没有 id/sessionId：%s'
                                 % json.dumps(sresp, ensure_ascii=False)[:200])
            t0 = time.time()
            timed_out = False
            answer = ''
            try:
                r = req(APP + '/api/chat', {'sessionId': sid, 'message': t['task']}, timeout=args.timeout)
                answer = r.get('data') or ''
                if not r.get('success', True):
                    answer = ''
            except Exception as e:
                timed_out = True
                answer = '（超时/异常：%s）' % e
            elapsed = time.time() - t0

            hist = req(APP + '/api/sessions/%s/history' % sid, timeout=60)
            msgs = hist.get('data') or []
            calls = []      # [(工具名, 参数, 原始文本)]
            errors = []     # 工具结果里的错误
            for m in msgs:
                if m.get('role') == 'assistant' and m.get('toolCalls'):
                    for tc in m['toolCalls']:
                        raw = tc.get('arguments')
                        if isinstance(raw, dict):
                            got = raw                       # 应用里存的就是 Map
                            raw = json.dumps(raw, ensure_ascii=False)
                        else:
                            raw = raw or ''
                            got = {}
                            try:
                                got = json.loads(raw) if raw.strip().startswith('{') else {}
                            except Exception:
                                got = {}
                            if not got:
                                got = {pm.group(1).strip(): pm.group(2).strip()
                                       for pm in PARAMETER.finditer(raw)}
                        calls.append((tc.get('name'), got, raw))
                if m.get('role') == 'tool':
                    c = m.get('content') or ''
                    if c.startswith('错误') or '❌' in c[:8] or '失败' in c[:40]:
                        errors.append(c[:120])

            first_name = calls[0][0] if calls else None
            first_args = calls[0][1] if calls else {}
            legal = bool(first_name) and first_name in spec and \
                all(a in first_args for a in spec.get(first_name, ()))
            first_ok = first_name == t['tool']
            if first_ok:
                # 参数也要对：期望值非 None 的必须一致（None = 只要求给了键）
                for k, want in t['args'].items():
                    if want is not None and normalize(first_args.get(k)) != normalize(want):
                        first_ok = False
            any_ok = any(c[0] == t['tool'] for c in calls)

            rows.append({'id': t['id'], 'task': t['task'], 'expect': t['tool'],
                         'calls': [c[0] for c in calls], 'first': first_name, 'legal': legal,
                         'first_ok': first_ok, 'any_ok': any_ok, 'errors': errors,
                         'rounds': len(calls), 'elapsed': round(elapsed, 1),
                         'timeout': timed_out, 'answer': (answer or '')[:120]})

            mark = '✓' if first_ok else ('△' if any_ok else '✗')
            print('  %2d %s %-44s → %-18s 轮=%d 错=%d %.0fs'
                  % (t['id'], mark, t['task'][:44], first_name or '（没调工具）',
                     len(calls), len(errors), elapsed))
            if not first_ok:
                print('       期望 %s；实际序列 %s' % (t['tool'], [c[0] for c in calls][:8]))

        n = len(rows)

        def pct(k):
            c = sum(1 for r in rows if r[k])
            return c, c * 100.0 / n

        legal_c, legal_p = pct('legal')
        first_c, first_p = pct('first_ok')
        any_c, any_p = pct('any_ok')
        clean_c = sum(1 for r in rows if not r['errors'])
        avg_rounds = sum(r['rounds'] for r in rows) / float(n)
        avg_secs = sum(r['elapsed'] for r in rows) / float(n)
        print()
        print('=' * 96)
        print('合法率（解析成功+工具存在+必需参数齐） : %d/%d = %.1f%%' % (legal_c, n, legal_p))
        print('首次选对率（第一次就挑对工具）        : %d/%d = %.1f%%' % (first_c, n, first_p))
        print('最终选对率（整轮里出现过期望工具）    : %d/%d = %.1f%%' % (any_c, n, any_p))
        print('零错误率（整轮没有任何工具报错）      : %d/%d = %.1f%%' % (clean_c, n, clean_c * 100.0 / n))
        print('平均工具调用轮数 %.1f；平均单条耗时 %.0f 秒；超时 %d 条'
              % (avg_rounds, avg_secs, sum(1 for r in rows if r['timeout'])))
        bad = [r for r in rows if not r['legal']]
        if bad:
            print('\n不合法（harness 层面的问题，必须修）：')
            for r in bad:
                print('  #%d 首次=%s 期望=%s 任务=%s' % (r['id'], r['first'], r['expect'], r['task']))
        err_rows = [r for r in rows if r['errors']]
        if err_rows:
            print('\n出现过工具报错的：')
            for r in err_rows:
                print('  #%d %s → %s' % (r['id'], r['task'][:40], r['errors'][0].replace('\n', ' ')[:90]))
        print('=' * 96)

        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        with open(args.out, 'w', encoding='utf-8') as f:
            json.dump({'legal': legal_p, 'first': first_p, 'any': any_p,
                       'clean': clean_c * 100.0 / n, 'rows': rows}, f, ensure_ascii=False, indent=2)
        print('明细写到 %s' % args.out)
    finally:
        stop_app(proc, log)
        kill_port(APP_PORT)


if __name__ == '__main__':
    main()

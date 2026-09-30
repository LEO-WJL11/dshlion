#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""上下文压测：**上下文满了会怎样、压缩对不对、快不快、压完模型还记不记得住**。

用户要的就是这四个问题的答案，所以这个脚本量四件事：

  ① 触发：把会话灌到超预算，看压缩到底有没有发生（读会话事件里的 CONTEXT_COMPRESSED），
     以及"第几轮"开始压。
  ② 正确性：压缩后请求还能不能正常发出去（以前没有任何上下文管理，超了就是服务端 400、
     整个会话永久坏掉）；每轮的 token 估算要能看到回落。
  ③ 速度：压缩是和请求同一条链路上的动作，所以这里直接量"压缩那一轮的耗时" vs
     "不压缩时每轮的耗时"——抽取式摘要应该是微秒级、用户完全无感。
  ④ 遗忘：压缩前植入三个事实（项目名 / 端口 / 代号），压完再问一遍，看模型答不答得上来。
     这一条是用户最关心的"压缩后模型遗忘如何"，必须用**真模型**量，不能靠推理。

用法：
    python tools/bench/_bench_context.py                 # 默认预算按系统提示词的 1.3 倍
    python tools/bench/_bench_context.py --budget 12000
    python tools/bench/_bench_context.py --filler 1200 --max-turns 24
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _app import start_app, stop_app, kill_port, req, wait_port  # noqa: E402


def estimate_tokens(text):
    """和 Java 侧 ContextCompressor 同一套经验公式：中日韩 1 token/字，其余 1 token/3.5 字符。"""
    cjk = 0
    other = 0
    for ch in (text or ''):
        if '\u2e80' <= ch <= '\u9fff' or '\uf900' <= ch <= '\ufaff' or '\uff00' <= ch <= '\uffef':
            cjk += 1
        else:
            other += 1
    return cjk + int(-(-other // 3.5))

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LLAMA = 'http://127.0.0.1:8788/v1'
APP_PORT = 8925
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionctx')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

FACTS = {'项目名': ['狮子盒', 'LionBox', 'lionbox'],
         '端口': ['8788'],
         '代号': ['LEO-7', 'LEO7', 'leo-7']}

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def chat(session_id, message, timeout=600):
    t0 = time.time()
    try:
        r = req(APP + '/api/chat', {'sessionId': session_id, 'message': message}, timeout=timeout)
        return (r.get('data') or '') if r.get('success', True) else ('（失败：%s）' % r.get('message')), time.time() - t0
    except Exception as e:
        return '（异常：%s）' % e, time.time() - t0


def compressions(session_id):
    """从事件流里数 CONTEXT_COMPRESSED（这是"压缩真的发生了"的硬证据）。"""
    try:
        r = req(APP + '/api/events/%s' % session_id, timeout=30)
        events = r.get('data') or []
    except Exception:
        return []
    out = []
    for e in events:
        if str(e.get('type') or '').upper() == 'CONTEXT_COMPRESSED':
            out.append(e.get('data') or {})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--budget', type=int, default=0, help='上下文预算（token），0 = 按系统提示词自动算')
    ap.add_argument('--filler', type=int, default=1200, help='每条填充消息的字符数')
    ap.add_argument('--max-turns', type=int, default=24)
    args = ap.parse_args()

    try:
        req(LLAMA + '/models', timeout=5)
    except Exception as e:
        raise SystemExit('本地模型服务不通（%s）' % e)

    kill_port(APP_PORT)
    import shutil
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
    os.makedirs(WS, exist_ok=True)
    with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
        json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
                   'baseUrl': LLAMA, 'apiKey': 'sk-local', 'model': 'lion-models1',
                   'toolCallMode': 'text'}, f, ensure_ascii=False)

    # 先起一个实例量系统提示词有多大（预算得比它大，否则每轮都在压，测不出"第几轮触发"）
    proc, log = start_app(APP_PORT, HOME, log_path=LOG,
                          extra_args=['--lionbox.agent.context-limit-tokens=999999999'])
    try:
        if not wait_port(APP_PORT):
            raise SystemExit('应用起不来，看日志 %s' % LOG)
        pv = req(APP + '/api/runtime/prompt-preview?mode=standard&message=x&full=true', timeout=60)
        system = (pv.get('data') or {}).get('systemPrompt') or ''
        sys_tokens = estimate_tokens(system)
        budget = args.budget or int(sys_tokens * 1.3)
        print('系统提示词约 %d token；本次预算取 %d token' % (sys_tokens, budget))
    finally:
        stop_app(proc, log)
        kill_port(APP_PORT)

    # 用真正的预算重启
    proc, log = start_app(APP_PORT, HOME, log_path=LOG,
                          extra_args=['--lionbox.agent.context-limit-tokens=%d' % budget,
                                      '--lionbox.agent.context-keep-recent=6'])
    try:
        if not wait_port(APP_PORT):
            raise SystemExit('应用起不来，看日志 %s' % LOG)
        wresp = req(APP + '/api/workspaces', {'path': WS})
        ws_id = (wresp.get('data') or {}).get('id') or (wresp.get('data') or {}).get('workspaceId')
        sresp = req(APP + '/api/sessions', {'workspaceId': ws_id, 'mode': 'standard'})
        sdata = sresp.get('data') or {}
        sid = sdata.get('sessionId') if isinstance(sdata, dict) else sdata
        print('会话 %s，预算 %d token' % (sid, budget))

        print('=' * 88)
        print('① 记忆植入：告诉它三个事实')
        print('=' * 88)
        seed = ('请记住下面三个事实，然后只回复「记住了」两个字，不要调用工具：\n'
                '1) 项目名是 狮子盒\n2) 服务端口是 8788\n3) 负责人代号是 LEO-7')
        ans, cost = chat(sid, seed)
        print('  模型回复：%s（%.0f 秒）' % (ans.strip().replace('\n', ' ')[:60], cost))

        print()
        print('=' * 88)
        print('② 灌上下文：每条 %d 字符的无关填充，看第几轮触发压缩' % args.filler)
        print('=' * 88)
        filler = ('下面是一段与任务无关的填充材料（用于把上下文灌满，请忽略内容）：\n'
                  + ('这是一段用来占位的说明文字，内容没有任何意义。' * (args.filler // 22))
                  + '\n请只回复 OK，不要调用任何工具。')
        turns = []
        first_compress_turn = None
        for i in range(1, args.max_turns + 1):
            ans, cost = chat(sid, filler, timeout=900)
            comp = compressions(sid)
            turns.append({'turn': i, 'secs': round(cost, 1), 'compressions': len(comp),
                          'answer': ans.strip()[:40]})
            mark = ''
            if comp and first_compress_turn is None:
                first_compress_turn = i
                mark = '  ← 压缩在这里触发（%s → %s token）' % (comp[-1].get('tokensBefore'),
                                                              comp[-1].get('tokensAfter'))
            print('  第 %2d 轮：%5.1f 秒，压缩累计 %d 次%s' % (i, cost, len(comp), mark))
            if len(comp) >= 3:
                break

        comp = compressions(sid)
        print()
        print('=' * 88)
        print('③ 压缩统计')
        print('=' * 88)
        if not comp:
            print('  ✗ 没触发压缩（预算 %d 太大或填充不够），下面的遗忘测试意义有限' % budget)
        else:
            before = [c.get('tokensBefore') for c in comp]
            after = [c.get('tokensAfter') for c in comp]
            print('  触发 %d 次；token：%s → %s' % (len(comp), before, after))
            print('  首次触发在第 %s 轮' % first_compress_turn)
            slow = max((t['secs'] for t in turns if t['compressions'] > 0), default=0)
            fast = min((t['secs'] for t in turns if t['compressions'] == 0), default=0)
            print('  单轮耗时：未压缩最快 %.1f 秒 / 压缩轮最慢 %.1f 秒' % (fast, slow))

        print()
        print('=' * 88)
        print('④ 遗忘测试：压完再问三个事实')
        print('=' * 88)
        ans, cost = chat(sid, '回答三个问题，每个一行，只给答案：项目名是什么？服务端口是多少？负责人代号是什么？')
        print('  模型回答：%s' % ans.strip().replace('\n', ' | ')[:200])
        hit = {}
        for name, keys in FACTS.items():
            hit[name] = any(k.lower() in ans.lower() for k in keys)
        got = sum(1 for v in hit.values() if v)
        print('  记得的事实：%d/3 %s' % (got, {k: ('✓' if v else '✗') for k, v in hit.items()}))
        print('  本次耗时 %.0f 秒' % cost)

        print()
        print('=' * 88)
        print('结论：压缩触发 %d 次；压缩后仍能正常对话：%s；事实保留 %d/3'
              % (len(comp), '是' if turns and turns[-1]['answer'] else '否', got))
        print('=' * 88)
        return 0 if (comp and got >= 2) else 1
    finally:
        stop_app(proc, log)
        kill_port(APP_PORT)


if __name__ == '__main__':
    sys.exit(main())

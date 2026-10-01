#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""上下文经济 + 改动人工审核：四条新能力的端到端用例。

【这一版为什么改成"假模型"驱动】第一版直接对着真模型发消息，结果四条工具链全红 ——
不是功能不行，是测试环境里根本没有可用的模型（本机 llama-server 没起），
`/api/chat` 受理了但模型那一步走空。测工具链就该用假模型：它按消息前缀吐确定的工具调用，
这样"工具到底干了什么"是可断言的事实，而不是"看运气模型听不听话"。
（真模型那一路另有压测/准确率用例在管。）

覆盖：
  1. 默认上下文窗口 = 16K；接口能改、能改回、非法值被拒；
  2. 模型调 context_window 工具 → 窗口**真的**变了（再查接口就是新值）；
  3. 模型调 context_prune 工具 → 会话历史**真的**变短（条数直接数）；
  4. 改动人工审核：write_file 不落盘 → 进待审 → 通过才写出来 → 打回则永不落盘且带理由。

用法：python tools/checks/_check_context_review.py
"""

import json
import os
import re
import shutil
import sys
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from bench import _app  # noqa: E402

MOCK_PORT = 8897
APP_PORT = 8947
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionctxreview')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
failed = []

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def check(label, ok, detail=''):
    print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
    if not ok:
        failed.append(label)
    return ok


def req(url, data=None, timeout=120, method=None):
    body = json.dumps(data).encode('utf-8') if data is not None else None
    r = urllib.request.Request(url, data=body, method=method or ('POST' if data is not None else 'GET'),
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8') or '{}')
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8') or '{}')
        except Exception:
            return {'success': False, 'error': 'HTTP %d' % e.code}


def call(tool, **params):
    """和产品里同一种文本工具调用格式（local 模式走文本通道）"""
    args = json.dumps(params, ensure_ascii=False)
    return '<tool_call>\n<function=%s>\n<parameter=%s>\n</function>\n</tool_call>' % (tool, args)


def new_session(mode='standard'):
    s = req(APP + '/api/sessions', {'workspaceId': WS_ID, 'mode': mode}, timeout=30)
    d = s.get('data') or {}
    return d.get('sessionId') if isinstance(d, dict) else d


def chat(session_id, message, timeout=180):
    r = req(APP + '/api/chat', {'sessionId': session_id, 'message': message}, timeout=timeout)
    return r.get('data') or ''


def history(session_id):
    r = req(APP + '/api/sessions/%s/history' % session_id, timeout=30)
    return r.get('data') or []


def changes(session_id, include_decided=False):
    q = urllib.parse.urlencode({'sessionId': session_id, 'includeDecided': str(include_decided).lower()})
    return req(APP + '/api/changes?' + q, timeout=30)


# ---------------------------------------------------------------- 假模型
class Mock(BaseHTTPRequestHandler):
    """按最后一条用户消息的前缀决定吐哪个工具调用；拿到工具结果就原样回给测试。"""

    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        try:
            body = json.loads(self.rfile.read(n).decode('utf-8', 'replace'))
        except Exception:
            body = {}
        msgs = body.get('messages') or []
        users = [str(m.get('content') or '') for m in msgs if m.get('role') == 'user']
        last_user = users[-1] if users else ''
        tools = [str(m.get('content') or '') for m in msgs if m.get('role') == 'tool']
        last_tool = tools[-1] if tools else ''

        if last_tool:
            # 已拿到工具结果：回给测试，断言直接看工具返回了什么
            content = 'TOOL_RESULT>>>' + last_tool
        elif last_user.startswith('WINDOW:'):
            content = call('context_window', tokens=32768, reason='用例：装不下')
        elif last_user.startswith('WINDOW-BAD:'):
            content = call('context_window', tokens=100)
        elif last_user.startswith('WRITE:'):
            path = last_user.split('WRITE:', 1)[1].strip()
            content = call('write_file', path=path, content='hello from AI')
        elif last_user.startswith('PRUNE:'):
            content = call('context_prune', keep_last=2, reason='前面的没用了')
        else:
            content = '好的。'

        payload = {
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2},
        }
        raw = json.dumps(payload).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def main():
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
    os.makedirs(WS, exist_ok=True)

    srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Mock)
    import threading
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    _app.write_config(HOME, {'providerMode': 'custom', 'provider': 'lionbox-custom',
                             'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT,
                             'apiKey': 'sk-mock', 'model': 'mock-model',
                             'toolCallMode': 'text'})
    _app.kill_port(APP_PORT)
    # 【关键】这个用例显式打开"改动人工审核"（别的用例默认关，见 tools/bench/_app.py 的注释）
    proc, log = _app.start_app(APP_PORT, HOME,
                               extra_args=['--lionbox.change-review.enabled=true'],
                               log_path=os.path.join(TMP, 'app.log'))
    global WS_ID
    try:
        if not _app.wait_port(APP_PORT, seconds=180):
            print(open(os.path.join(TMP, 'app.log'), encoding='utf-8', errors='replace').read()[-3000:])
            return check('应用起来了', False)
        check('应用起来了（假模型已就位）', True)

        w = req(APP + '/api/workspaces', {'path': WS}, timeout=30)
        WS_ID = (w.get('data') or {}).get('id') or (w.get('data') or {}).get('workspaceId') or WS
        sessions = new_session()

        # ---------------- 1) 默认 16K ----------------
        st = req(APP + '/api/context', timeout=30)
        ctx = st.get('context', {})
        check('★ 默认上下文窗口是 16K', ctx.get('defaultLimit') == 16384,
              'defaultLimit=%s' % ctx.get('defaultLimit'))
        check('未调过时会话窗口=默认、overridden=false',
              ctx.get('sessionLimit') == 16384 and ctx.get('overridden') is False, str(ctx))

        # ---------------- 2) 模型用 context_window 工具改窗口 ----------------
        ans = chat(sessions, 'WINDOW: 把窗口调大')
        check('★ 工具返回"已改为 32K"，并提醒做完调回来',
              '上下文窗口已改为' in ans and '32K' in ans and '调回' in ans, ans.strip()[:200])
        ctx = req(APP + '/api/context?sessionId=%s' % sessions, timeout=30).get('context', {})
        check('★ 窗口**真的**变了（再查就是这个会话 32K）',
              ctx.get('sessionLimit') == 32768 and ctx.get('overridden') is True, str(ctx))
        other = new_session()
        ctx_other = req(APP + '/api/context?sessionId=%s' % other, timeout=30).get('context', {})
        check('别的会话不受影响（窗口是按会话记的）',
              ctx_other.get('sessionLimit') == 16384 and ctx_other.get('overridden') is False,
              str(ctx_other))

        # 调回去
        ans = chat(sessions, 'WINDOW: 再调小')
        check('★ 再调一次就把窗口改回去了（约束里要求的"用完还"）',
              '上下文窗口已改为' in ans and '32K' in ans, ans.strip()[:160])

        # 非法值：太小
        s2 = new_session()
        ans = chat(s2, 'WINDOW-BAD: 调成 100')
        check('太小被拒（下限 4096），并说明原因', '至少' in ans and '4096' in ans, ans.strip()[:160])

        # ---------------- 3) 裁剪上下文：真变短 ----------------
        s3 = new_session()
        for i in range(6):
            chat(s3, '记住第 %d 件事' % i)
        n_before = len(history(s3))
        ans = chat(s3, 'PRUNE: 前面没用了')
        n_after = len(history(s3))
        check('★ 裁剪工具报告删了几条、留了几条',
              '已裁剪' in ans and '保留最近' in ans, ans.strip()[:200])
        check('★ 会话历史**真的**变短了', n_after < n_before,
              '%d → %d 条' % (n_before, n_after))
        check('裁剪后至少保留最近 2 条（不会把当前这轮删没）', n_after >= 2, n_after)

        # ---------------- 4) 改动人工审核 ----------------
        target = os.path.join(WS, 'review_me.txt')
        s4 = new_session()
        info = req(APP + '/api/changes?sessionId=%s' % s4, timeout=30)
        check('★ 审核开关开着（这个实例显式打开）', info.get('enabled') is True, str(info.get('enabled')))

        ans = chat(s4, 'WRITE: %s' % target)
        check('★ 工具明确回了"已提交人工审核、还没落盘"',
              '人工审核' in ans and '还没落盘' in ans, ans.strip()[:220])
        check('★ 文件**没有**落盘（等人工点通过）', not os.path.exists(target), target)

        pend = changes(s4).get('changes', [])
        check('★ 改动进了待审列表', len(pend) == 1, 'pendingCount=%s' % len(pend))
        if pend:
            c = pend[0]
            check('待审记录带路径和 diff',
                  c.get('path') == target and 'hello from AI' in (c.get('diff') or ''),
                  (c.get('diff') or '')[:120])
            cid = c['id']

            # 通过
            r = req(APP + '/api/changes/%s/approve' % cid, {}, timeout=60)
            check('★ 通过返回成功', r.get('success') is True, str(r.get('message'))[:120])
            check('★ 通过之后文件真的写出来了',
                  os.path.isfile(target) and 'hello from AI' in open(target, encoding='utf-8').read(),
                  open(target, encoding='utf-8').read() if os.path.isfile(target) else '文件不存在')
            c2 = [x for x in changes(s4, True).get('changes', []) if x['id'] == cid]
            check('记录状态变成 APPROVED', c2 and c2[0]['status'] == 'APPROVED',
                  c2[0]['status'] if c2 else 'no record')

        # 打回：不落盘 + 理由
        target2 = os.path.join(WS, 'reject_me.txt')
        s5 = new_session()
        chat(s5, 'WRITE: %s' % target2)
        pend2 = changes(s5).get('changes', [])
        check('★ 第二条改动也进了待审', len(pend2) == 1, 'pendingCount=%s' % len(pend2))
        if pend2:
            cid2 = pend2[0]['id']
            r = req(APP + '/api/changes/%s/reject' % cid2, {'reason': '内容不对，换成中文重写'}, timeout=60)
            check('★ 打回返回成功', r.get('success') is True, str(r.get('message'))[:120])
            check('★ 打回之后文件永远不落盘', not os.path.exists(target2), target2)
            rec = req(APP + '/api/changes/%s' % cid2, timeout=30).get('change', {})
            check('打回理由记在记录里',
                  rec.get('status') == 'REJECTED' and '中文' in (rec.get('reason') or ''),
                  '%s / %s' % (rec.get('status'), rec.get('reason')))
            hist = json.dumps(history(s5), ensure_ascii=False)
            check('★ 会话里留下了"被打回、请重写"的提示（模型下一轮照着改）',
                  '打回' in hist and '中文' in hist, hist[-160:])

        # 老行为：关掉审核插件 → 直接落盘
        rd = req(APP + '/api/plugins/plugin.change-review/disable', {}, timeout=30)
        check('关掉审核插件这个动作本身成功',
              rd.get('success') is not False, json.dumps(rd, ensure_ascii=False)[:160])
        check('★ 关掉之后 /api/changes 报 enabled=false',
              req(APP + '/api/changes', timeout=30).get('enabled') is False,
              str(req(APP + '/api/changes', timeout=30).get('enabled')))
        s6 = new_session()
        target3 = os.path.join(WS, 'no_review.txt')
        chat(s6, 'WRITE: %s' % target3)
        check('★ 关掉审核插件后恢复老行为（直接落盘）', os.path.isfile(target3), target3)
        req(APP + '/api/plugins/plugin.change-review/enable', {}, timeout=30)

    finally:
        _app.stop_app(proc, log)
        srv.shutdown()
        shutil.rmtree(TMP, ignore_errors=True)

    print()
    print('结果：%s' % ('全部通过' if not failed else '失败 %d 项：%s' % (len(failed), failed)))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""插件系统**功能性**回归：九类插件里"真干活"的那几类，一条条真跑。

为什么单独写这个套件：`_check_plugin_system.py` 验的是"列表/开关/热插拔/脚手架"这些**机制**，
但用户要的九类插件里，子智能体、智能体团队、自动授权审查、自动化任务这四类是"最小实现"，
机制通过不代表它们真能跑起来。这个套件用假模型把每条链路串起来端到端跑一遍：

  1. agent_spawn 真能派子智能体（新会话 + 子 Agent 跑完 + 结论带回主 Agent）
  2. 递归层级上限真拦得住（maxDepth=0 时派发被拒，且拒绝话能回到模型）
  3. 并发上限真拦得住（maxConcurrency=0 时派发被拒）
  4. agent_team_run 真能按配置的成员分头干活再汇总
  5. 自动授权审查：另一个模型说 DENY → 工具**不执行**、理由回给模型
  6. 自动授权审查：另一个模型说 ALLOW → 工具正常执行
  7. 自动化任务：到点自动往会话里投递消息（不是只躺在列表里），投完不重复触发
  8. Agent 大循环插件：设置 maxIterations 后真的会停，并给出可读的停止原因
  9. 终端插件：maxOutputBytes / maxCommandSeconds 真的生效（读设置、不是读死值）

端口：应用 8936，假模型 8893（都不碰用户的 8080/8788）。
用法：python tools/checks/_check_plugin_extras.py
"""

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'bench'))
import _app  # noqa: E402

MOCK_PORT = 8893
APP_PORT = 8936
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionpluginextra')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

PASS = []
FAIL = []


def check(label, ok, detail=''):
    print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
    (PASS if ok else FAIL).append(label)


def req(url, data=None, timeout=120, method=None):
    body = None if data is None else json.dumps(data).encode('utf-8')
    r = urllib.request.Request(url, data=body, method=method,
                               headers={'Content-Type': 'application/json'} if body else {})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'replace'))


def call(tool, **params):
    """拼一个文本通道的工具调用块。第一个参数叫 tool 不叫 name：
    工具自己也可能有个叫 name 的参数（get_env），叫 name 会和 **params 撞车
    —— 第一版就是这么把假模型写崩的（TypeError: got multiple values for argument 'name'）。"""
    inner = ''.join('<parameter=%s>%s</parameter>' % (k, v) for k, v in params.items())
    return '<tool_call><function=%s>%s</function></tool_call>' % (tool, inner)


def plugin_list(payload):
    """从 /api/plugins 的返回里取插件数组。
    后端**同时**给新老两种格式：顶层 plugins = 新格式（有 kind/enabled/source），
    data = 老格式（只有 type/initialized）。要验 kind 就得挑新格式那份 ——
    第一版随手拿了 data，结果 kind 全是 None，"整组开关"那条断言白判失败。
    """
    cands = []
    if isinstance(payload, dict):
        d = payload.get('data')
        if isinstance(d, dict) and isinstance(d.get('plugins'), list):
            cands.append(d['plugins'])
        if isinstance(d, list):
            cands.append(d)
        if isinstance(payload.get('plugins'), list):
            cands.append(payload['plugins'])
    for c in cands:
        if c and isinstance(c[0], dict) and 'kind' in c[0]:
            return c
    return cands[0] if cands else []


def plugin_settings(payload):
    if isinstance(payload, dict):
        data = payload.get('data')
        if isinstance(data, dict):
            return data
        return payload
    return {}


# ---------------------------------------------------------------- 假模型
class Mock(BaseHTTPRequestHandler):
    """按会话/消息内容决定回什么，让每条链路都能被确定性地驱动。"""

    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        try:
            body = json.loads(self.rfile.read(n).decode('utf-8', 'replace'))
        except Exception:
            body = {}
        msgs = body.get('messages') or []
        sysmsg = str(msgs[0].get('content')) if msgs and msgs[0].get('role') == 'system' else ''
        users = [str(m.get('content') or '') for m in msgs if m.get('role') == 'user']
        last_user = users[-1] if users else ''
        tools = [str(m.get('content') or '') for m in msgs if m.get('role') == 'tool']
        last_tool = tools[-1] if tools else ''

        content = '（空）'
        # 【顺序很重要】先判"已经拿到工具结果"的情况，否则假模型会在工具报错后**一直吐同一个调用**，
        # 把测试跑成无限循环（第一版就踩了：日志里同一个 execute_command 调了 56 次，
        # 客户端等超时才断——那不是产品的问题，是这里的假模型不够真）。
        # 1) 子智能体：子 Agent 的提示词是我们自己写的，认这句话
        if '被主 Agent 派来做一件具体事情' in last_user:
            content = '子智能体结论：这件事我做完了，结果是 42。'
        # 2) 团队成员：认"你是团队里的【X】"
        elif '你是团队里的【' in last_user:
            who = re.search(r'你是团队里的【([^】]+)】', last_user)
            content = '【%s】我负责的部分做完了。' % (who.group(1) if who else '成员')
        # 3) 自动授权审查：审查器我们给它一句专属 system
        elif '工具调用安全审核员' in sysmsg:
            content = self.server.verdict
        # 4) 大循环那条要**故意一直调工具**（测的就是"能不能被轮次上限拦住"），但设个上限
        #    免得产品那边上限失效时把测试跑死
        elif last_user.startswith('LOOP:') and len(tools) < 20:
            content = call('timestamp', format='%H:%M')
        # 5) 已经拿到工具结果：把结果原样回给测试（断言就能直接看工具返回了什么）
        elif last_tool:
            content = 'TOOL_RESULT>>>' + last_tool
        # 6) 主 Agent 的第一轮：按消息前缀决定调用什么工具
        elif last_user.startswith('SPAWN:'):
            content = call('agent_spawn', task='统计 src 目录下有多少行代码')
        elif last_user.startswith('TEAM:'):
            content = call('agent_team_run', task='把这次发布的风险过一遍')
        elif last_user.startswith('REVIEW:'):
            content = call('execute_command', command='echo hello-review')
        elif last_user.startswith('OUTPUT:'):
            content = call('execute_command', command='1..400 | ForEach-Object { "line $_" }')
        elif last_user.startswith('SLOW:'):
            content = call('execute_command', command='Start-Sleep -Seconds 120')
        elif last_user.startswith('MANY:') and not tools:
            # 一轮里一次给 3 个互不依赖的调用，用来看"一轮最多几个"这条设置
            content = (call('timestamp', format='%H:%M')
                       + call('system_info')
                       + call('get_env', name='PATH'))
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


def main():
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
    os.makedirs(WS, exist_ok=True)
    with open(os.path.join(WS, 'a.txt'), 'w', encoding='utf-8') as f:
        f.write('a\nb\nc\n')

    srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Mock)
    srv.verdict = 'ALLOW 看起来安全'
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    _app.write_config(HOME, {'providerMode': 'custom', 'provider': 'lionbox-custom',
                             'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT,
                             'apiKey': 'sk-mock', 'model': 'mock-model',
                             'toolCallMode': 'text'})
    _app.kill_port(APP_PORT)
    proc, log = _app.start_app(APP_PORT, HOME, log_path=os.path.join(TMP, 'app.log'))
    global WS_ID
    try:
        if not _app.wait_port(APP_PORT):
            raise SystemExit('应用起不来：%s' % os.path.join(TMP, 'app.log'))
        w = req(APP + '/api/workspaces', {'path': WS}, timeout=30)
        WS_ID = (w.get('data') or {}).get('id') or (w.get('data') or {}).get('workspaceId')

        print('=' * 88)
        print('1) agent_spawn 真的能派子智能体')
        print('=' * 88)
        req(APP + '/api/plugins/settings/subagent', {'maxDepth': 2, 'maxConcurrency': 2}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'SPAWN: 统计一下代码行数')
        check('派发链路通了：主 Agent 拿到了子智能体的结论',
              '子智能体结论' in ans and '42' in ans, ans.strip()[:120])
        sessions = req(APP + '/api/sessions', timeout=30).get('data') or []
        names = [s.get('name') or '' for s in sessions if isinstance(s, dict)]
        check('子智能体真的开了一个独立会话（名字带"子智能体："）',
              any('子智能体' in n for n in names), [n for n in names if '子智能体' in n][:2])

        print()
        print('=' * 88)
        print('2) 递归层级上限真的拦得住')
        print('=' * 88)
        req(APP + '/api/plugins/settings/subagent', {'maxDepth': 0}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'SPAWN: 再来一次')
        check('maxDepth=0 时派发被拒，且拒绝话回到了模型',
              '层级上限' in ans, ans.strip()[:160])
        req(APP + '/api/plugins/settings/subagent', {'maxDepth': 2}, timeout=30)

        print()
        print('=' * 88)
        print('3) 并发上限真的拦得住')
        print('=' * 88)
        req(APP + '/api/plugins/settings/subagent', {'maxConcurrency': 0}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'SPAWN: 并发试试')
        check('maxConcurrency=0 时派发被拒',
              ('并发' in ans and '上限' in ans), ans.strip()[:160])
        req(APP + '/api/plugins/settings/subagent', {'maxConcurrency': 2}, timeout=30)

        print()
        print('=' * 88)
        print('4) agent_team_run 按配置的成员分头干活')
        print('=' * 88)
        req(APP + '/api/plugins/team', {'id': 'reviewer', 'name': '审查员', 'mode': 'minimal',
                                        'role': '看代码风险', 'enabled': True}, timeout=30)
        req(APP + '/api/plugins/team', {'id': 'writer', 'name': '文档员', 'mode': 'standard',
                                        'role': '写发布说明', 'enabled': True}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'TEAM: 把这次发布过一遍')
        check('两个成员都真的跑了、结论都汇总回来了',
              '【审查员】' in ans and '【文档员】' in ans, ans.strip()[:200])

        print()
        print('=' * 88)
        print('5) 自动授权审查：DENY → 工具不执行')
        print('=' * 88)
        req(APP + '/api/plugins/plugin.approval-review/enable', {}, timeout=30)
        req(APP + '/api/plugins/settings/review',
            {'tools': ['execute_command'], 'model': 'reviewer-model'}, timeout=30)
        srv.verdict = 'DENY 这条命令会改系统状态，先别跑'
        sid = new_session()
        ans = chat(sid, 'REVIEW: 跑个命令')
        check('审查模型说 DENY 时，理由是"被自动授权审查拦下"',
              '自动授权审查拦下' in ans, ans.strip()[:200])
        check('被拦下的工具确实没有执行（工具结果里没有 hello-review 的输出）',
              'hello-review' not in ans, ans.strip()[:200])

        print()
        print('=' * 88)
        print('6) 自动授权审查：ALLOW → 工具正常执行')
        print('=' * 88)
        srv.verdict = 'ALLOW 只读命令，放行'
        sid = new_session()
        ans = chat(sid, 'REVIEW: 再跑一次')
        check('审查模型说 ALLOW 时工具真的执行了',
              'hello-review' in ans, ans.strip()[:200])
        req(APP + '/api/plugins/plugin.approval-review/disable', {}, timeout=30)

        print()
        print('=' * 88)
        print('7) 自动化任务：到点自动往会话里投递')
        print('=' * 88)
        sid = new_session()
        before = len([m for m in history(sid) if m.get('role') == 'user'])
        r = req(APP + '/api/plugins/automation',
                {'id': 'auto1', 'name': '每5秒一次', 'sessionId': sid,
                 'prompt': '定时任务来敲门', 'everySeconds': 5, 'enabled': True}, timeout=30)
        check('任务建出来了', r.get('success') is not False, json.dumps(r, ensure_ascii=False)[:120])
        got = False
        for _ in range(40):      # 轮询间隔 15 秒，等够
            time.sleep(1)
            users = [str(m.get('content') or '') for m in history(sid) if m.get('role') == 'user']
            if any('定时任务来敲门' in u for u in users):
                got = True
                break
        check('到点后消息真的进了会话（不是只躺在任务列表里）', got,
              '等了 %d 秒' % 0 if got else '等了 40 秒还没动')
        due = req(APP + '/api/plugins/automation/due', timeout=30).get('data') or []
        check('投递后记账了（markRun：暂时不再重复触发）',
              all((d.get('task') or {}).get('id') != 'auto1' for d in due),
              json.dumps(due, ensure_ascii=False)[:120])
        req(APP + '/api/plugins/automation/auto1', method='DELETE', timeout=30)

        print()
        print('=' * 88)
        print('8) Agent 大循环插件：maxIterations 真的会停')
        print('=' * 88)
        req(APP + '/api/plugins/settings/loop', {'maxIterations': 2}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'LOOP: 一直调用工具别停')
        check('到轮次上限就停了，并说清了原因',
              '最大工具调用轮数' in ans, ans.strip()[:160])
        req(APP + '/api/plugins/settings/loop', {'maxIterations': 0}, timeout=30)

        print()
        print('=' * 88)
        print('9) 终端插件：输出上限与命令超时真的读设置')
        print('=' * 88)
        req(APP + '/api/plugins/settings/terminal', {'maxOutputBytes': 300}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'OUTPUT: 打一堆行出来')
        check('输出超过 300 字节被截断且明确告知',
              '已截断' in ans or '截断' in ans, ans.strip()[:200])
        req(APP + '/api/plugins/settings/terminal', {'maxCommandSeconds': 3}, timeout=30)
        sid = new_session()
        t0 = time.time()
        ans = chat(sid, 'SLOW: 睡两分钟')
        cost = time.time() - t0
        check('命令超时按设置生效（3 秒就放弃，而不是等到天荒地老）',
              cost < 60 and ('超时' in ans or 'timeout' in ans.lower()),
              '%.0f 秒：%s' % (cost, ans.strip()[:120]))
        req(APP + '/api/plugins/settings/terminal',
            {'maxOutputBytes': 200000, 'maxCommandSeconds': 300}, timeout=30)

        print()
        print('=' * 88)
        print('10) 大循环插件：一轮最多几个工具调用（"控制派发方式"那一项）')
        print('=' * 88)
        sid = new_session()
        ans = chat(sid, 'MANY: 一次给我三个工具')
        check('不限（默认）时三个调用都执行了、没有"未执行"这类话',
              'TOOL_RESULT' in ans and '未执行' not in ans, ans.strip()[:160])
        req(APP + '/api/plugins/settings/loop', {'maxToolsPerRound': 1}, timeout=30)
        sid = new_session()
        ans = chat(sid, 'MANY: 再来三个')
        check('设成 1 之后，多出来的调用被明确告知"没执行、下一轮继续"',
              '未执行' in ans and '上限' in ans, ans.strip()[:200])
        req(APP + '/api/plugins/settings/loop', {'maxToolsPerRound': 0}, timeout=30)

        print()
        print('=' * 88)
        print('11) 整组开关（按类型一次全开/全关）')
        print('=' * 88)
        r = req(APP + '/api/plugins/kind/ADVANCED_TOOL/disable', {}, timeout=60)
        rd = plugin_settings(r)
        check('整组关闭返回处理数量', rd.get('count', 0) > 20, json.dumps(rd, ensure_ascii=False)[:120])
        items = plugin_list(req(APP + '/api/plugins', timeout=30))
        adv = [p for p in items if str(p.get('kind') or '').upper() == 'ADVANCED_TOOL']
        check('这一组确实全关了', bool(adv) and all(p.get('enabled') is False for p in adv),
              '%d 个，还开着 %d 个' % (len(adv), sum(1 for p in adv if p.get('enabled'))))
        pv = req(APP + '/api/runtime/prompt-preview?mode=standard&full=true', timeout=60)
        sp = (pv.get('data') or {}).get('systemPrompt') or ''
        check('关掉的工具真的从标准模式提示词里消失了（web_search 不在）',
              'web_search' not in sp)
        req(APP + '/api/plugins/kind/ADVANCED_TOOL/enable', {}, timeout=60)
        items = plugin_list(req(APP + '/api/plugins', timeout=30))
        adv = [p for p in items if str(p.get('kind') or '').upper() == 'ADVANCED_TOOL']
        check('再整组打开就都回来了', bool(adv) and all(p.get('enabled') is True for p in adv),
              '开着 %d 个' % sum(1 for p in adv if p.get('enabled')))
    finally:
        _app.stop_app(proc, log)
        _app.kill_port(APP_PORT)
        srv.shutdown()

    print()
    print('=' * 88)
    print('结果：%d 通过 / %d 失败' % (len(PASS), len(FAIL)))
    if FAIL:
        print('失败项：')
        for f in FAIL:
            print('  - %s' % f)
    print('=' * 88)
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())

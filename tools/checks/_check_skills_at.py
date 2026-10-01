# -*- coding: utf-8 -*-
r"""技能通用格式 + 对话 @ 引用 —— 单独验一遍。

要证明的事情（每条都是用户能感知的行为）：

技能（通用 SKILL.md 格式）
  ① 四个内置技能从仓库 skills/ 读出来了，frontmatter 各字段解析正确；
  ② 用户技能目录（~/.lioncode/skills）里的技能能被发现，同名覆盖内置；
  ③ 坏掉的 SKILL.md（frontmatter 缺字段）进 error 字段，**应用照样起得来**；
  ④ 技能目录被注入系统提示词（模型能自己挑），skill_load 工具真的存在、真的能取回正文；
  ⑤ 关键词命中的技能正文照旧注入（老行为没丢）；
  ⑥ /api/skills/{id}/enable|disable 能持久化（写进 app-config.json）；
  ⑦ POST /api/skills/active 钉住的技能，正文每轮都在上下文里。

@ 引用
  ⑧ @file: 展开后模型确实看到了文件内容（用假模型截获请求体来断言，不看日志猜）；
  ⑨ @history: 展开后带"这是历史会话不是当前会话"的标注；
  ⑩ @skill: 展开成技能正文；
  ⑪ 展开这件事在事件流里看得见（context_expand 事件）；
  ⑫ /api/context/mentions 能按关键词找到文件/历史对话/技能；
  ⑬ 大目录检索不超时（2 秒上限）、依赖目录（node_modules）不进候选。

端口纪律：本套件只用 8930-8939（8930=假模型，8931=被测应用），绝不碰 8080/8788。
构建产物用 target/ 下那个 jar；也可以用环境变量指到别的 jar 上：
  LIONBOX_CHECK_JAR=<jar 路径> LIONBOX_SKILLS_DIR=<技能目录>
"""
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.environ.get('LIONBOX_CHECK_JAR') or os.path.join(
    ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
SKILLS_DIR = os.environ.get('LIONBOX_SKILLS_DIR') or os.path.join(ROOT, 'skills')
MOCK_PORT = 8930
APP_PORT = 8931
APP = 'http://127.0.0.1:%d' % APP_PORT

TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionskillat')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
WSROOT = os.path.join(TMP, 'wsroot')
LOG = os.path.join(TMP, 'app.log')

# 假模型看到过的请求体（断言"模型到底收到了什么"全靠它）
SEEN = []
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


# ----------------------------------------------------------------------
# 假模型：按脚本回文本通道的工具调用，并把每个请求体记下来
# ----------------------------------------------------------------------

def tool_call_block(name, args):
    body = ''.join('<parameter=%s>%s</parameter>\n' % (k, v) for k, v in args.items())
    return '<tool_call>\n<function=%s>\n%s</function>\n</tool_call>' % (name, body)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def _json(self, obj):
        body = json.dumps(obj).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith('/__seen'):
            return self._json({'seen': SEEN})
        if self.path.startswith('/__reset'):
            SEEN.clear()
            return self._json({'ok': True})
        return self._json({'object': 'list', 'data': [{'id': 'mock-model', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        SEEN.append(payload)
        msgs = payload.get('messages') or []
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        is_title = ('会话标题生成器' in joined) or ('短标题' in joined)
        tool_msgs = [m for m in msgs if m.get('role') == 'tool']
        last_user = ''
        for m in msgs:
            if m.get('role') == 'user':
                last_user = str(m.get('content') or '')

        if is_title:
            content = '标题'
        elif tool_msgs:
            # 工具结果已经回给模型了：这一轮收尾（不能再回工具调用，否则死循环）
            content = '技能已按指令执行完毕。'
        elif '技能工具' in last_user:
            # 让模型主动用 skill_load —— 验证"模型自己选用技能"这条路真的通
            content = tool_call_block('skill_load', {'name': 'backend'})
        else:
            content = '看完了，收到。'

        if payload.get('stream'):
            return self._sse(content)
        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})

    def _sse(self, content):
        """流式请求也要能回（有的通道会带 stream=true），格式照 OpenAI 的 chunk。"""
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'close')
        self.end_headers()

        def emit(obj):
            self.wfile.write(('data: ' + json.dumps(obj, ensure_ascii=False) + '\n\n').encode('utf-8'))
            self.wfile.flush()

        emit({'id': 'chunk-0', 'object': 'chat.completion.chunk', 'model': 'mock-model',
              'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': content},
                           'finish_reason': None}]})
        emit({'id': 'chunk-end', 'object': 'chat.completion.chunk', 'model': 'mock-model',
              'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}]})
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()


# ----------------------------------------------------------------------
# 小工具
# ----------------------------------------------------------------------

def real_java():
    home = os.environ.get('JAVA_HOME')
    if home and os.path.isfile(os.path.join(home, 'bin', 'java.exe')):
        return os.path.join(home, 'bin', 'java.exe')
    for base in (r'C:\Program Files\Java', r'C:\Program Files\Eclipse Adoptium'):
        if os.path.isdir(base):
            for d in sorted(os.listdir(base)):
                p = os.path.join(base, d, 'bin', 'java.exe')
                if os.path.isfile(p):
                    return p
    return shutil.which('java') or 'java'


def req(url, method='GET', body=None, timeout=120):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return json.loads(raw)
        except Exception:
            return {'success': False, 'error': 'HTTP %d: %s' % (e.code, raw[:200])}


def kill_port(port):
    out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)
    # kill 完要等端口真空出来，否则下一个 app 绑不上、请求又打到旧进程上
    deadline = time.time() + 20
    while time.time() < deadline:
        rows = subprocess.run(['netstat', '-ano', '-p', 'TCP'],
                              capture_output=True, text=True).stdout.splitlines()
        busy = any(len(x.split()) >= 5 and x.split()[1].endswith(':' + str(port))
                   and x.split()[3] == 'LISTENING' for x in rows)
        if not busy:
            return
        time.sleep(0.5)


def start_app():
    log = open(LOG, 'a', encoding='utf-8', errors='replace')
    args = [real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME]
    if os.environ.get('LIONBOX_SKILLS_DIR'):
        args.append('-Dlion.skills.dir=' + SKILLS_DIR)
    args += ['-jar', JAR,
             '--server.port=%d' % APP_PORT,
             '--lion.workspace.default-path=' + WSROOT,
             '--lionbox.runtime.auto-download=false',
             # 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，开着审核
             # 文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）
             '--lionbox.change-review.enabled=false',
             '--lionbox.runtime.prewarm.enabled=false']
    # cwd 故意放在 TMP：技能目录必须靠"jar 在哪"自己找出来，不能依赖工作目录
    proc = subprocess.Popen(args, cwd=TMP, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(120):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            return proc, log
        except Exception:
            time.sleep(1)
    return proc, log


def write(path, text, encoding='utf-8'):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding=encoding, newline='\n') as f:
        f.write(text)


def seen_texts():
    """假模型看到过的所有消息文本（拼一起方便子串断言）。"""
    out = []
    for payload in SEEN:
        for m in payload.get('messages') or []:
            out.append(str(m.get('content') or ''))
    return '\n'.join(out)


def reset_seen():
    SEEN.clear()


def last_user_payload(marker):
    """找"这一轮请求"（最后一条 user 消息里带 marker 的那个请求体）。"""
    for payload in reversed(SEEN):
        msgs = payload.get('messages') or []
        for m in reversed(msgs):
            if m.get('role') == 'user':
                if marker in str(m.get('content') or ''):
                    return payload
                break
    return None


# ----------------------------------------------------------------------
# 准备环境
# ----------------------------------------------------------------------

print('=' * 72)
print('技能通用格式 + 对话 @ 引用')
print('=' * 72)

kill_port(APP_PORT)
kill_port(MOCK_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WSROOT, exist_ok=True)

# 工作区里的素材
write(os.path.join(WS, 'hello.txt'),
      'LIONBOX_AT_FILE_MARKER 你好，这是被 @ 引用的文件。\n第二行内容。\n')
write(os.path.join(WS, 'deep', 'pack', 'mentions_target_alpha.txt'), 'AAA\n')
for i in range(30):
    write(os.path.join(WS, 'deep', 'pack', 'noise_%02d.txt' % i), 'x\n')
# 依赖目录：里面的文件绝不能出现在候选里（否则用户敲什么都会看到 node_modules）
write(os.path.join(WS, 'bigtree', 'node_modules', 'left-pad', 'should_not_appear.txt'), 'x\n')
write(os.path.join(WS, 'bigtree', 'node_modules', 'left-pad', 'index.js'), 'x\n')
for d in range(40):
    for i in range(60):
        write(os.path.join(WS, 'bigtree', 'src%02d' % d, 'file_%02d_%03d.txt' % (d, i)), 'x\n')

# 用户技能目录：一个正常技能、一个覆盖内置的同名技能、一个坏技能
write(os.path.join(HOME, '.lioncode', 'skills', 'myskill', 'SKILL.md'), """---
name: myskill
display_name: 我的测试技能
description: 用户自己加的技能，用来验证用户目录能被扫到
when_to_use: 用户提到 myskill 关键词时
keywords: [myskill, 用户技能]
tools: [read_file]
mode: standard
---
用户技能正文标记 USER_SKILL_BODY_MARKER
""")

write(os.path.join(HOME, '.lioncode', 'skills', 'frontend', 'SKILL.md'), """---
name: frontend
display_name: 用户覆盖版前端技能
description: 用户目录里的同名技能，应该覆盖仓库内置的 frontend
when_to_use: 用户提到页面样式时
keywords: [页面, 样式]
tools: [read_file]
mode: standard
---
用户覆盖版正文标记 USER_OVERRIDE_BODY_MARKER
""")

write(os.path.join(HOME, '.lioncode', 'skills', 'broken', 'SKILL.md'), """---
name: broken
display_name: 坏掉的技能
---
这份技能缺 description 和 when_to_use，应该进 error 字段而不是把应用弄崩。
""")

# 应用配置：走自定义 API（指到假模型），文本通道
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

mock = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=mock.serve_forever, daemon=True).start()

proc, log = start_app()
try:
    if proc.poll() is not None:
        print('  应用没起来，日志尾部：')
        print(open(LOG, encoding='utf-8', errors='replace').read()[-3000:])
        sys.exit(1)

    # ---- 注册工作区 + 建会话 ----
    wsreg = req(APP + '/api/workspaces', 'POST', {'path': WS})
    ws_id = (wsreg.get('data') or {}).get('id') or WS

    def new_session():
        r = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
        return (r.get('data') or {}).get('sessionId')

    SID_A = new_session()
    SID_B = new_session()
    # 会话 A 的**第一条**消息带上标记：历史检索（按标题/首条消息匹配）才有东西可找
    req(APP + '/api/chat', 'POST',
        {'sessionId': SID_A, 'message': 'ALPHA-7788 请记住这个标记'})

    # ==================================================================
    # ① 技能列表 / frontmatter 解析
    # ==================================================================
    print('\n[1] 技能列表与 frontmatter 解析')
    skills_res = req(APP + '/api/skills')
    skills = skills_res.get('skills') or []
    by_id = {s.get('id'): s for s in skills}
    check('GET /api/skills 返回成功且带技能数组',
          skills_res.get('ok') is True and skills_res.get('success') is True and len(skills) > 0,
          'ok=%s skills=%d' % (skills_res.get('ok'), len(skills)))
    check('★ 四个内置技能都在（backend/client/document/frontend）',
          all(i in by_id for i in ('backend', 'client', 'document', 'frontend')),
          str(sorted(by_id.keys())))
    check('★ 内置技能目录指向仓库 skills/',
          os.path.isdir(skills_res.get('skillsDir') or '')
          and 'skills' in (skills_res.get('skillsDir') or ''),
          str(skills_res.get('skillsDir')))
    check('★ 用户技能目录是 <配置目录>/skills（~/.lioncode/skills）',
          (skills_res.get('userSkillsDir') or '').replace('/', '\\').lower()
          == os.path.join(HOME, '.lioncode', 'skills').lower(),
          str(skills_res.get('userSkillsDir')))

    be = by_id.get('backend') or {}
    check('★ frontmatter 解析正确（displayName/description/whenToUse）',
          be.get('displayName') == '后端开发技能' and '后端代码编写' in (be.get('description') or '')
          and len(be.get('whenToUse') or '') > 5,
          '%s | %s' % (be.get('displayName'), (be.get('whenToUse') or '')[:30]))
    check('★ keywords/tools/mode 解析正确',
          '后端' in (be.get('keywords') or []) and 'read_file' in (be.get('tools') or [])
          and be.get('mode') == 'standard',
          'kw=%s tools=%s mode=%s' % (be.get('keywords'), be.get('tools'), be.get('mode')))
    check('★ 正文读到了（bodyPreview/bodyLines 非空）',
          len(be.get('bodyPreview') or '') > 20 and (be.get('bodyLines') or 0) > 3
          and be.get('enabled') is True and be.get('builtin') is True
          and not (be.get('error') or ''),
          'lines=%s err=%s' % (be.get('bodyLines'), be.get('error')))

    # ==================================================================
    # ② 用户技能 + 同名覆盖 + 坏技能
    # ==================================================================
    print('\n[2] 用户技能 / 同名覆盖 / 坏技能不崩')
    check('★ 用户目录里的技能被扫到（myskill, builtin=false）',
          (by_id.get('myskill') or {}).get('builtin') is False
          and '用户自己加的技能' in ((by_id.get('myskill') or {}).get('description') or ''),
          str((by_id.get('myskill') or {}).get('description')))
    check('★ 同名用户技能覆盖内置（frontend 用的是用户那份）',
          (by_id.get('frontend') or {}).get('builtin') is False
          and (by_id.get('frontend') or {}).get('displayName') == '用户覆盖版前端技能',
          str((by_id.get('frontend') or {}).get('displayName')))
    broken = by_id.get('broken') or {}
    check('★ 坏 SKILL.md 进了 error 字段，列表里看得到',
          'broken' in by_id and 'description' in (broken.get('error') or ''),
          str(broken.get('error')))
    check('★ 坏技能不影响应用（列表接口照样正常返回）',
          req(APP + '/api/skills').get('ok') is True and broken.get('enabled') is False,
          'enabled=%s' % broken.get('enabled'))

    # ==================================================================
    # ③ 提示词注入 + skill_load 工具
    # ==================================================================
    print('\n[3] 技能目录注入 / skill_load 工具')
    pv = req(APP + '/api/runtime/prompt-preview?mode=standard&message=%s&full=true'
             % urllib.parse.quote('随便聊聊'))
    sp = (pv.get('data') or {}).get('systemPrompt') or ''
    check('★ 系统提示词里有技能目录（每个技能一行）',
          '技能（按需加载）' in sp and '- backend — 后端开发技能' in sp,
          'prompt chars=%d 有目录头=%s 有 backend 行=%s'
          % (len(sp), '技能（按需加载）' in sp, '- backend — 后端开发技能' in sp))
    check('★ 目录里明确要求模型先调 skill_load',
          'skill_load' in sp and '技能 id' in sp)
    plugins = req(APP + '/api/plugins').get('data') or []
    check('★ skill_load 工具已注册（ToolPlugin）',
          any(p.get('name') == 'skill_load' and p.get('type') == 'TOOL' for p in plugins),
          str([p.get('name') for p in plugins if 'skill' in str(p.get('name'))]))

    pv2 = req(APP + '/api/runtime/prompt-preview?mode=standard&message=%s&full=true'
              % urllib.parse.quote('帮我改一下页面样式'))
    sp2 = (pv2.get('data') or {}).get('systemPrompt') or ''
    check('★ 关键词命中的技能正文照旧注入（且用的是用户覆盖版正文）',
          'USER_OVERRIDE_BODY_MARKER' in sp2,
          'frontend 覆盖版正文是否出现: %s' % ('USER_OVERRIDE_BODY_MARKER' in sp2))

    # ==================================================================
    # ④ @file 展开（真对话 + 假模型截获请求体）
    # ==================================================================
    print('\n[4] @file 引用：模型真的看到了文件内容')
    reset_seen()
    r = req(APP + '/api/chat', 'POST',
            {'sessionId': SID_A, 'message': '@file:hello.txt 这个文件写了什么？'})
    check('@file 消息发送成功（接口没被破坏）', r.get('success') is True, str(r.get('error'))[:120])
    payload = last_user_payload('这个文件写了什么')
    user_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '这个文件写了什么' in str(m.get('content') or ''):
                user_text = str(m.get('content'))
    check('★ 模型收到的消息里有文件内容（LIONBOX_AT_FILE_MARKER）',
          'LIONBOX_AT_FILE_MARKER' in user_text, 'len=%d' % len(user_text))
    check('★ 引用块有明确的首尾标注',
          '--- 引用开始：文件 hello.txt' in user_text and '--- 引用结束：文件 hello.txt' in user_text)
    events = req(APP + '/api/events/%s' % SID_A).get('data') or []
    expand_events = [e for e in events
                     if (e.get('data') or {}).get('toolName') == 'context_expand']
    check('★ 展开这件事在事件流里看得见（context_expand 事件）',
          len(expand_events) > 0 and '@file:hello.txt' in str(expand_events[-1].get('summary')),
          str(expand_events[-1].get('summary'))[:120] if expand_events else '没有事件')

    # ==================================================================
    # ⑤ @history 展开
    # ==================================================================
    print('\n[5] @history 引用：历史对话内容 + 防混淆标注')
    reset_seen()
    req(APP + '/api/chat', 'POST',
        {'sessionId': SID_B, 'message': '@history:%s 之前聊了什么？' % SID_A})
    payload = last_user_payload('之前聊了什么')
    hist_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '之前聊了什么' in str(m.get('content') or ''):
                hist_text = str(m.get('content'))
    check('★ @history 展开出了历史会话的内容（ALPHA-7788）',
          'ALPHA-7788' in hist_text, 'len=%d' % len(hist_text))
    check('★ 历史内容明确标注了"不是当前会话"（防模型混淆）',
          '不是当前会话' in hist_text and '历史会话' in hist_text)

    # ==================================================================
    # ⑥ @skill / /skill 展开 + 会话钉住技能
    # ==================================================================
    print('\n[6] @skill 指定技能 / POST /api/skills/active')
    reset_seen()
    req(APP + '/api/chat', 'POST', {'sessionId': SID_B, 'message': '@skill:document 帮我写文档'})
    payload = last_user_payload('帮我写文档')
    skill_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '帮我写文档' in str(m.get('content') or ''):
                skill_text = str(m.get('content'))
    check('★ @skill:document 的正文被展开进这一轮上下文',
          '你具备专业的文档处理能力' in skill_text, 'len=%d' % len(skill_text))

    reset_seen()
    req(APP + '/api/chat', 'POST', {'sessionId': SID_B, 'message': '/skill:client 做桌面端'})
    payload = last_user_payload('做桌面端')
    slash_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '做桌面端' in str(m.get('content') or ''):
                slash_text = str(m.get('content'))
    check('★ /skill:client 这种命令写法也认',
          '你具备专业的桌面客户端开发能力' in slash_text, 'len=%d' % len(slash_text))

    active_res = req(APP + '/api/skills/active', 'POST',
                     {'sessionId': SID_B, 'skills': ['backend', 'nope']})
    check('POST /api/skills/active 能钉技能（不认识的 id 会被忽略并提示）',
          active_res.get('ok') is True and active_res.get('unknown') == ['nope'],
          str(active_res.get('unknown')))
    active_get = req(APP + '/api/skills/active?sessionId=%s' % SID_B)
    check('★ GET /api/skills/active 读回钉住的技能',
          active_get.get('skills') == ['backend'], str(active_get.get('skills')))
    reset_seen()
    req(APP + '/api/chat', 'POST', {'sessionId': SID_B, 'message': '现在做点别的'})
    payload = last_user_payload('现在做点别的')
    sys_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'system':
                sys_text = str(m.get('content') or '')
    check('★ 钉住的技能，正文每轮都在系统提示词里（不用模型去 load）',
          '你具备专业的后端开发能力' in sys_text and '本轮指定技能' in sys_text)

    # ==================================================================
    # ⑦ 模型自己选技能：skill_load 端到端
    # ==================================================================
    print('\n[7] 模型自己选用技能：skill_load 端到端')
    reset_seen()
    req(APP + '/api/chat', 'POST', {'sessionId': SID_B, 'message': '用技能工具帮我干活'})
    tool_result = ''
    for payload in SEEN:
        for m in payload.get('messages') or []:
            # 【注意】OpenAI 的 tool 消息不一定带 name 字段（本项目回传的就没有），
            # 所以按 role 收全部工具结果，再看内容里有没有技能正文
            if m.get('role') == 'tool':
                c = str(m.get('content') or '')
                if '后端开发能力' in c or '请按下面这套做法' in c:
                    tool_result = c
    check('★ 假模型调 skill_load 后，工具结果里是技能正文',
          '你具备专业的后端开发能力' in tool_result, 'len=%d' % len(tool_result))
    check('★ skill_load 的结果里带"请按这套做法"的引导语',
          '请按下面这套做法' in tool_result)

    # ==================================================================
    # ⑧ 启用/禁用持久化
    # ==================================================================
    print('\n[8] 技能启用/禁用与持久化')
    dis = req(APP + '/api/skills/backend/disable', 'POST', {})
    cfg_path = os.path.join(HOME, '.lioncode', 'app-config.json')
    cfg_after_disable = json.load(open(cfg_path, encoding='utf-8'))
    check('disable 返回 {ok,id,enabled:false}',
          dis.get('ok') is True and dis.get('id') == 'backend' and dis.get('enabled') is False,
          str(dis.get('enabled')))
    check('★ 禁用状态写进了 app-config.json（持久化，重启也不丢）',
          'backend' in (cfg_after_disable.get('skills.disabled') or []),
          str(cfg_after_disable.get('skills.disabled')))
    one = [s for s in (req(APP + '/api/skills').get('skills') or []) if s.get('id') == 'backend']
    pv_dis = req(APP + '/api/runtime/prompt-preview?mode=standard&message=%s&full=true'
                 % urllib.parse.quote('随便聊聊'))
    sp_dis = (pv_dis.get('data') or {}).get('systemPrompt') or ''
    check('★ 禁用后列表里 backend.enabled=false，技能目录里也不再出现',
          one and one[0].get('enabled') is False and '- backend —' not in sp_dis,
          'enabled=%s 目录里还在=%s' % (one[0].get('enabled') if one else 'missing',
                                    '- backend —' in sp_dis))
    m_dis = req(APP + '/api/context/mentions?q=%s&kind=skill' % urllib.parse.quote('后端'))
    check('禁用后 @ 补全里也找不到它',
          not any(i.get('insert') == '@skill:backend' for i in (m_dis.get('items') or [])),
          str([i.get('insert') for i in (m_dis.get('items') or [])]))
    en = req(APP + '/api/skills/backend/enable', 'POST', {})
    cfg_after_enable = json.load(open(cfg_path, encoding='utf-8'))
    check('enable 把它放回来（配置里也清掉了）',
          en.get('enabled') is True and 'backend' not in (cfg_after_enable.get('skills.disabled') or []),
          str(cfg_after_enable.get('skills.disabled')))

    rel = req(APP + '/api/skills/reload', 'POST', {})
    check('POST /api/skills/reload 返回 {ok,count,skills}',
          rel.get('ok') is True and rel.get('count') == len(rel.get('skills') or [])
          and rel.get('count') >= 6,
          'count=%s' % rel.get('count'))

    # ==================================================================
    # ⑨ @ 补全检索
    # ==================================================================
    print('\n[9] /api/context/mentions 检索')
    m_file = req(APP + '/api/context/mentions?q=mentions_target&kind=file&root=%s'
                 % urllib.parse.quote(WS))
    items = m_file.get('items') or []
    check('★ 按关键词找到文件，并给出可直接插入的 @file:',
          any(i.get('type') == 'file'
              and i.get('insert') == '@file:deep/pack/mentions_target_alpha.txt' for i in items),
          str([i.get('insert') for i in items])[:160])
    check('mentions 响应同时给 ok/items 和 data.items（前端两种写法都能取）',
          m_file.get('ok') is True
          and (m_file.get('data') or {}).get('items') == items)

    m_skill = req(APP + '/api/context/mentions?q=%s&kind=skill' % urllib.parse.quote('后端'))
    s_items = m_skill.get('items') or []
    check('★ 能按关键词找到技能（insert=@skill:backend）',
          any(i.get('type') == 'skill' and i.get('insert') == '@skill:backend' for i in s_items),
          str([i.get('insert') for i in s_items]))

    m_hist = req(APP + '/api/context/mentions?q=ALPHA-7788&kind=history')
    h_items = m_hist.get('items') or []
    check('★ 能按关键词找到历史对话（insert=@history:<id>）',
          any(i.get('type') == 'history' and i.get('id') == SID_A for i in h_items),
          str([i.get('id') for i in h_items]))

    m_hidden = req(APP + '/api/context/mentions?q=should_not_appear&kind=file&root=%s'
                   % urllib.parse.quote(WS))
    check('★ node_modules 里的文件不进候选（依赖目录被跳过）',
          (m_hidden.get('items') or []) == [], str(m_hidden.get('items'))[:120])

    m_big = req(APP + '/api/context/mentions?q=&kind=file&root=%s&limit=20' % urllib.parse.quote(WS))
    check('★ 大目录检索不超时（2 秒上限生效）',
          m_big.get('ok') is True and (m_big.get('elapsedMs') or 99999) <= 2100,
          'elapsedMs=%s scanned=%s' % (m_big.get('elapsedMs'), m_big.get('scannedFiles')))
    check('大目录检索有结果（不是空转）',
          len(m_big.get('items') or []) > 0
          and (m_big.get('scannedFiles') or 0) > 100,
          'items=%d scanned=%s' % (len(m_big.get('items') or []), m_big.get('scannedFiles')))

    m_bad = req(APP + '/api/context/mentions?q=x&kind=file&root=%s'
                % urllib.parse.quote(os.path.join(TMP, 'does-not-exist')))
    check('根目录不存在时明确提示（不抛 500）',
          m_bad.get('ok') is True and (m_bad.get('items') or []) == []
          and '不存在' in str(m_bad.get('note')),
          str(m_bad.get('note'))[:100])

    # ==================================================================
    # ⑩ 引用失败要给出能照着改的提示
    # ==================================================================
    print('\n[10] 引用失败/越界的处理')
    reset_seen()
    req(APP + '/api/chat', 'POST', {'sessionId': SID_B, 'message': '@file:nope-not-here.txt 看看'})
    payload = last_user_payload('看看')
    fail_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '看看' in str(m.get('content') or ''):
                fail_text = str(m.get('content'))
    check('★ 文件不存在时给明确提示，而不是抛异常/吞消息',
          '引用失败' in fail_text and '文件不存在' in fail_text, fail_text[:160].replace('\n', ' '))

    reset_seen()
    req(APP + '/api/chat', 'POST',
        {'sessionId': SID_B, 'message': '@file:../../../../Windows/win.ini 读它'})
    payload = last_user_payload('读它')
    outside_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '读它' in str(m.get('content') or ''):
                outside_text = str(m.get('content'))
    check('★ 越界路径被拒绝（工作区外的文件读不到）',
          '已拒绝' in outside_text and '引用失败' in outside_text,
          outside_text[:160].replace('\n', ' '))

    # ==================================================================
    # ⑪ 流式接口同样要展开（前端走的是 /api/chat/stream）
    # ==================================================================
    print('\n[11] 流式接口的 @ 引用')
    reset_seen()
    # 【为什么要新开一个会话】同步消息跑完后，那个会话的 worker 还在（空闲 30 秒才退出），
    # 流式接口此时会回"会话正忙，请等待当前任务完成" —— 这是既有行为，不是 @ 引用的问题。
    SID_C = new_session()
    sbody = json.dumps({'sessionId': SID_C, 'message': '@file:hello.txt 流式也要看到内容'},
                       ensure_ascii=False).encode('utf-8')
    sreq = urllib.request.Request(APP + '/api/chat/stream', data=sbody, method='POST',
                                  headers={'Content-Type': 'application/json',
                                           'Accept': 'text/event-stream'})
    stream_raw = ''
    try:
        with urllib.request.urlopen(sreq, timeout=60) as resp:
            stream_raw = resp.read().decode('utf-8', 'replace')
    except Exception as e:
        stream_raw = 'stream 调用异常: %s' % e
    payload = last_user_payload('流式也要看到内容')
    stream_text = ''
    if payload:
        for m in payload.get('messages') or []:
            if m.get('role') == 'user' and '流式也要看到内容' in str(m.get('content') or ''):
                stream_text = str(m.get('content'))
    check('★ 流式接口里 @file 也展开了（两条路一套行为）',
          'LIONBOX_AT_FILE_MARKER' in stream_text,
          'len=%d 流式响应=%s' % (len(stream_text), stream_raw[:90].replace('\n', ' ')))

finally:
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    time.sleep(1)
    kill_port(APP_PORT)
    log.close()
    try:
        mock.shutdown()
    except Exception:
        pass

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)

# -*- coding: utf-8 -*-
r"""回归测试：一切皆插件（9 类插件 / 设置开关 / 热插拔 / 开发模式 / 终端限制）。

用户原话：
  "插件要有基础工具插件、进阶工具插件、四个技能插件、子智能体插件、终端插件、
   agent 大循环插件、智能体团队插件、自动授权审查插件、自动化任务插件；
   都要能热插拔；要有插件开发模式；用户要能在设置里选择每个插件是否开启。"

所以这里验的是**这些承诺真的成立**，而不是"接口返回 200"：
  1. 列表里 9 类插件都在，工具按"极简模式能不能用"自动分成基础/进阶；
  2. 关掉一个插件后，它的工具**从下发给模型的清单里消失**（不是只改个字段）；
  3. 关掉的状态**重启后还在**（落盘在 <插件目录>/settings.json）；
  4. 终端插件的两条限制真的生效：输出超限截断并提示、命令超时按设置秒数掐；
  5. 热插拔：坏 jar 不影响启动（列表里带 error 看得见）、外部 jar 能加载能卸载；
  6. 插件开发模式：scaffold 生成的工程**能编译**、编译出来的 jar **能加载**；
  7. 子智能体/团队/审查/自动化四类插件的配置端点可用、参数校验到位。

端口只用 8920-8929，临时 HOME/工作区，绝不碰 8080（用户正在用的实例）和 8788（本地模型）。
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
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8893
APP_PORT = 8921          # 第一段
APP_PORT2 = 8922         # 重启后的第二段（验证持久化）
APP_PORT3 = 8923         # 坏设置文件那一段（**必须换端口**：万一上一个实例没死透，
                         # 同端口会让新实例绑定失败，而测试连到旧实例上会得到"看起来对"的假结论）
APP = 'http://127.0.0.1:%d' % APP_PORT
APP2 = 'http://127.0.0.1:%d' % APP_PORT2
APP3 = 'http://127.0.0.1:%d' % APP_PORT3
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionplugin')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
EVENTS = os.path.join(TMP, 'events')
DEFAULT_WS = os.path.join(TMP, 'default-ws')
LOG = os.path.join(TMP, 'app1.log')
LOG2 = os.path.join(TMP, 'app2.log')
LOG3 = os.path.join(TMP, 'app3.log')

ok_all = True
CHECKS = []


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    CHECKS.append((label, bool(ok)))
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


def warn(label, detail=''):
    print('  [WARN] ' + label + (('  ' + str(detail)) if detail else ''))


def req(url, method='GET', body=None, timeout=600):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'replace'))


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


def find_jdk_tool(name):
    """找 javac/jar：优先 JAVA_HOME，其次 PATH。找不到返回 None。"""
    home = os.environ.get('JAVA_HOME')
    if home:
        p = os.path.join(home, 'bin', name + '.exe')
        if os.path.isfile(p):
            return p
        p = os.path.join(home, 'bin', name)
        if os.path.isfile(p):
            return p
    return shutil.which(name)


def subrun(cmd, timeout=300, cwd=None):
    """跑子进程并**按 UTF-8 解码**输出。

    为什么必须显式指定编码：Windows 上 Python 默认用 GBK 解子进程输出，
    而 javac / PowerShell 在中文环境下会吐 UTF-8 中文，解码线程直接抛
    UnicodeDecodeError，把真正的失败原因吃掉（第一版就是这么把 build.ps1 的
    报错吞掉的）。
    """
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=cwd,
                          encoding='utf-8', errors='replace')


def kill_port(port):
    out = subrun(['netstat', '-ano', '-p', 'TCP'], timeout=60).stdout or ''
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)
    deadline = time.time() + 20
    while time.time() < deadline:
        rows = (subrun(['netstat', '-ano', '-p', 'TCP'], timeout=60).stdout or '').splitlines()
        busy = any(len(x.split()) >= 5 and x.split()[1].endswith(':' + str(port))
                   and x.split()[3] == 'LISTENING' for x in rows)
        if not busy:
            return
        time.sleep(0.5)


# ======================================================================
# Mock 模型服务：按用户消息决定"这一轮模型该回什么"
# ======================================================================
PROMPTS = []       # 每次请求的 system prompt
TOOL_RESULTS = []  # 每次请求里带回的工具结果
ALL_TEXT = []      # 每次请求的全部消息（原文），用于"这次对话到底看到了什么"的整体断言
EXTRA = []         # 对话之外的信息（HTTP 返回正文等），同样参与整体断言


def block(name, args):
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
            return self._json({'prompts': PROMPTS, 'toolResults': TOOL_RESULTS})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        system = ' '.join(str(m.get('content') or '') for m in msgs if m.get('role') == 'system')
        user = ''
        for m in msgs:
            if m.get('role') == 'user':
                user = str(m.get('content') or '')
        tools = [str(m.get('content') or '') for m in msgs if m.get('role') == 'tool']
        PROMPTS.append(system)
        TOOL_RESULTS.append(tools)
        ALL_TEXT.append(json.dumps(msgs, ensure_ascii=False))

        if '会话标题生成器' in system or '短标题' in system:
            content = '插件测试'
        elif '清单测试' in user:
            content = '清单看过了。'
        elif '截断测试' in user and not tools:
            # 故意打一大堆输出，触发终端插件的"最大输出字节数"
            content = block('execute_command', {
                'command': '1..400 | ForEach-Object { "LIONBIG-" + $_ + "-" + ("x" * 60) }'})
        elif '超时测试' in user and not tools:
            # 故意睡很久，触发终端插件的"每条命令最长运行秒数"
            content = block('execute_command', {'command': 'Start-Sleep -Seconds 40', 'timeout': '30'})
        elif tools:
            content = '工具跑完了。'
        else:
            content = '好的。'

        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})


class QuietServer(ThreadingHTTPServer):
    """客户端（被测应用）中途断开时不要往控制台吐一堆栈 —— 那是正常现象。"""

    daemon_threads = True

    def handle_error(self, request, client_address):
        pass


srv = QuietServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()


# ======================================================================
# 起停应用
# ======================================================================
def start_app(port, log_path):
    log = open(log_path, 'w', encoding='utf-8', errors='replace')
    proc = subprocess.Popen([
        real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
        '--server.port=%d' % port,
        # 这两条必须显式给：application.yml 里的 ${user.home} 在打包时就被 Maven 资源过滤
        # 替换成了构建机的家目录（见 docs/插件系统.md 的"已知坑"），
        # 不覆盖的话测试会去读写用户真实的工作区目录。
        '--lion.workspace.default-path=' + DEFAULT_WS,
        '--lion.event.store-path=' + EVENTS,
        '--lionbox.runtime.auto-download=false',
        '--lionbox.runtime.prewarm.enabled=false'],
        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def wait_ready(base, timeout=180):
    """等应用就绪，并且等**插件都注册完**。

    为什么不能只看端口通不通：Spring 的 Web 端口在所有单例初始化完就开了，
    而工具/技能/系统插件是 ApplicationRunner 里注册的（在那之后）。
    端口一通就查列表会只看到一半插件 —— 那是测试自己在抢跑，不是产品的问题。
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            j = req(base + '/api/plugins', timeout=5)
            ids = {p['id'] for p in (j.get('plugins') or [])}
            last = len(ids)
            if len(ids) >= 60 and 'plugin.automation' in ids and 'tool.file.read' in ids:
                return j
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('应用/插件未在 %d 秒内就绪（最后一次看到插件数=%s）' % (timeout, last))


def plugin_of(listing, pid):
    for p in listing.get('plugins') or []:
        if p['id'] == pid:
            return p
    return None


def listed_tools(prompt):
    """取出提示词里「## 可用工具（N 个）」真正列出的工具名。

    【为什么必须只解析这一节】整段提示词里到处都有工具名：
    调用格式的示例写死了 read_file，末尾那张"别选错工具"对照表是**静态文案**，
    里面点名了 execute_command / run_background。
    拿"整段里有没有出现这个词"当判据，插件关了也永远显示"还在"，测试就成了假警报。
    模型真正能看到的工具清单，就是这一节。
    """
    m = re.search(r'## 可用工具（(\d+) 个）\n(.*?)\n\n', prompt, re.S)
    if not m:
        return []
    names = []
    for line in m.group(2).splitlines():
        if line.startswith('- '):
            names.append(line[2:].split('(')[0].strip())
    return names


def window_text(start):
    """这一段之后，模型看到过的、用户看到过的全部文字（含工具结果与报错）。

    为什么不能只看最后一条工具结果：工具**失败**时主循环可能不再回模型一轮，
    那条错误只出现在返回给用户的答复里；而标题生成之类的旁路请求又会插进来。
    按"这一段时间里的所有请求 + 所有 HTTP 答复"来判断，才不会被时序细节骗到。
    """
    req_start, extra_start = start if isinstance(start, tuple) else (start, 0)
    return '\n'.join(ALL_TEXT[req_start:] + EXTRA[extra_start:])


def window_tools(start):
    """这一段对话里"下发给模型的工具清单"（取窗口内第一条带清单的提示词）。

    为什么不直接看 PROMPTS[start]：一次聊天里可能先来一条"生成会话标题"的请求
    （它的系统提示词里没有工具清单），抢在真正那条请求前面。按窗口扫、取第一个有清单的。
    """
    for p in PROMPTS[start:]:
        names = listed_tools(p)
        if names:
            return names
    return []


def mark():
    """记下当前进度（请求数 + 额外信息数），配 window_text 用"""
    return (len(ALL_TEXT), len(EXTRA))


def ask(base, sid, text):
    resp = req(base + '/api/chat', 'POST',
               {'sessionId': sid, 'message': text, 'model': 'mock-model',
                'thinkingLevel': 'MEDIUM'}, timeout=900)
    # 答复正文也要留档：工具失败时"用户到底看到了什么"只有这里能证明
    EXTRA.append(json.dumps(resp, ensure_ascii=False))
    return resp


# ======================================================================
print('=' * 72)
print('一切皆插件：9 类插件 / 开关持久化 / 热插拔 / 开发模式 / 终端限制')
print('=' * 72)

kill_port(APP_PORT)
kill_port(APP_PORT2)
kill_port(APP_PORT3)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)

if not os.path.isfile(JAR):
    print('找不到 %s，先构建（python tools/dev/_mvn.py -o -q -DskipTests package）' % JAR)
    sys.exit(1)

# 走 mock 模型 + 文本工具通道（工具清单进提示词，才能验"关掉的工具不会下发"）
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

proc, log = start_app(APP_PORT, LOG)
proc2, log2 = None, None
try:
    listing = wait_ready(APP)
    plugins = listing['plugins']
    kinds = {k['name']: k for k in listing['kinds']}

    # ---------- A 列表与分类 ----------
    check('GET /api/plugins 同时给新老两种格式（ok 与 success 都在）',
          listing.get('ok') is True and listing.get('success') is True)
    check('插件列表非空且包含全部内置工具/技能/系统插件（>=60）',
          len(plugins) >= 60, '实际 %d 个' % len(plugins))
    check('9 类插件分类齐全',
          len(listing['kinds']) == 9 and set(kinds) == {
              'BASE_TOOL', 'ADVANCED_TOOL', 'SKILL', 'SUBAGENT', 'TERMINAL',
              'AGENT_LOOP', 'AGENT_TEAM', 'APPROVAL_REVIEW', 'AUTOMATION'},
          ','.join(sorted(kinds)))
    empty = [k for k, v in kinds.items() if v['count'] <= 0]
    check('每一类都至少有一个插件（没有空分类）', not empty, '空的分类: %s' % empty)
    check('工具自动分类正确：read_file=基础工具、web_search=进阶工具',
          (plugin_of(listing, 'tool.file.read') or {}).get('kind') == 'BASE_TOOL'
          and (plugin_of(listing, 'tool.web.search') or {}).get('kind') == 'ADVANCED_TOOL',
          'read_file=%s web_search=%s' % ((plugin_of(listing, 'tool.file.read') or {}).get('kind'),
                                          (plugin_of(listing, 'tool.web.search') or {}).get('kind')))
    check('四个技能插件都归到 SKILL 类', kinds['SKILL']['count'] == 4,
          'SKILL=%d' % kinds['SKILL']['count'])
    check('pluginsDir 用的是运行时 user.home（不受打包时写死的路径影响）',
          os.path.normcase(listing['pluginsDir']).startswith(os.path.normcase(HOME)),
          listing['pluginsDir'])
    check('所有插件都带齐展示字段（displayName/version/source/builtin/hotReloadable）',
          all(all(k in p for k in ('displayName', 'version', 'source', 'builtin',
                                   'hotReloadable', 'enabled', 'error'))
              for p in plugins))
    check('默认开关：内置插件默认开启，只有自动授权审查默认关闭',
          (plugin_of(listing, 'tool.file.read') or {}).get('enabled') is True
          and (plugin_of(listing, 'plugin.approval-review') or {}).get('enabled') is False,
          'approval-review.enabled=%s' % (plugin_of(listing, 'plugin.approval-review') or {}).get('enabled'))
    check('系统插件 6 个都注册上了（终端/大循环/子智能体/团队/审查/自动化）',
          all(plugin_of(listing, pid) is not None for pid in (
              'plugin.terminal', 'plugin.agent-loop', 'plugin.subagent',
              'plugin.agent-team', 'plugin.approval-review', 'plugin.automation')))

    # ---------- B 设置开关：真的生效 + 重启还在 ----------
    # 建工作区 + 会话（后面的对话都用它）
    w = req(APP + '/api/workspaces', 'POST', {'path': WS})
    ws_id = (w.get('data') or {}).get('workspaceId') or (w.get('data') or {}).get('id')
    s = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid = (s.get('data') or {}).get('sessionId')

    # 先拿一条"什么都没关"的基线
    before = len(PROMPTS)
    ask(APP, sid, '清单测试：看看你现在能用哪些工具')
    base_tools = window_tools(before)
    check('文本通道下提示词里带着可用工具清单（模型唯一的工具说明）',
          'read_file' in base_tools and 'list_directory' in base_tools and len(base_tools) >= 50,
          '清单里 %d 个工具' % len(base_tools))

    r = req(APP + '/api/plugins/tool.file.read/disable', 'POST', {})
    check('POST /api/plugins/{id}/disable 返回 {ok,id,enabled}',
          r.get('ok') is True and r.get('id') == 'tool.file.read' and r.get('enabled') is False,
          json.dumps(r, ensure_ascii=False)[:120])

    listing = req(APP + '/api/plugins')
    check('关掉之后列表里 enabled=false',
          (plugin_of(listing, 'tool.file.read') or {}).get('enabled') is False)

    before = len(PROMPTS)
    ask(APP, sid, '清单测试：再报一次你现在能用哪些工具')
    now_tools = window_tools(before)
    check('★ 关掉的插件，它的工具从下发给模型的清单里消失（read_file 不再列出）',
          bool(now_tools) and 'read_file' not in now_tools,
          '关掉后清单 %d 个工具' % len(now_tools))
    check('对照组：没关掉的工具照常在清单里（list_directory 仍在）',
          'list_directory' in now_tools)
    check('清单数量正好少了一个（说明是精准摘掉，不是整份没变）',
          len(now_tools) == len(base_tools) - 1,
          '%d → %d' % (len(base_tools), len(now_tools)))

    # ---------- C 终端插件：两条限制 ----------
    r = req(APP + '/api/plugins/settings/terminal', 'POST',
            {'maxOutputBytes': 700, 'maxCommandSeconds': 60})
    check('终端限制可写可读（maxOutputBytes=700）',
          r.get('success') is True and (r.get('data') or {}).get('maxOutputBytes') == 700,
          json.dumps(r.get('data'), ensure_ascii=False)[:150])

    # 会跑工具的两段对话各用一个**新会话**：mock 靠"这轮有没有工具结果"决定要不要发工具调用，
    # 老会话里带着上一轮的工具结果会让它误判（第一版就是这么让超时用例根本没触发的）。
    s2 = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid_cut = (s2.get('data') or {}).get('sessionId')
    s3 = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid_timeout = (s3.get('data') or {}).get('sessionId')

    before = mark()
    ask(APP, sid_cut, '截断测试：跑一条输出很长的命令')
    big = window_text(before)
    check('★ 输出超过上限时被截断，并且明确告诉模型「已截断」',
          '已截断' in big and 'LIONBIG-' in big, big[-160:].replace('\n', ' '))

    r = req(APP + '/api/plugins/settings/terminal', 'POST', {'maxCommandSeconds': 3})
    check('终端限制可改（maxCommandSeconds=3）',
          (r.get('data') or {}).get('maxCommandSeconds') == 3)
    before = mark()
    t0 = time.time()
    ask(APP, sid_timeout, '超时测试：跑一条很久的命令')
    dt = time.time() - t0
    text = window_text(before)
    check('★ 命令超过设置秒数就被掐断（明确提示超时，并说明是几秒）',
          '超时' in text and re.search(r'3\s*秒', text) is not None, text[-160:].replace('\n', ' '))
    check('★ 掐断按设置的 3 秒走，没有等模型请求的 30 秒', dt < 60, '耗时 %.1fs' % dt)

    before = len(PROMPTS)
    req(APP + '/api/plugins/plugin.terminal/disable', 'POST', {})
    ask(APP, sid, '清单测试：终端关掉之后还有什么')
    tools_off = window_tools(before)
    check('★ 关掉终端插件后，它管的一组 shell 工具也一起从清单消失',
          bool(tools_off) and not ({'execute_command', 'run_background', 'stop_background'}
                                   & set(tools_off)),
          '剩下的 shell 工具: %s' % sorted({'execute_command', 'run_background',
                                             'stop_background'} & set(tools_off)))
    check('关掉终端插件不会误伤别的工具（list_directory 还在清单里）',
          'list_directory' in tools_off)
    req(APP + '/api/plugins/plugin.terminal/enable', 'POST', {})

    # ---------- D 大循环 / 子智能体 / 团队 / 审查 / 自动化 ----------
    r = req(APP + '/api/plugins/settings/loop', 'POST',
            {'maxIterations': 7, 'toolTimeoutSeconds': 33, 'silentRounds': 1})
    d = r.get('data') or {}
    check('Agent 大循环插件：最大轮次/工具超时/空转容忍可配置',
          d.get('maxIterations') == 7 and d.get('toolTimeoutSeconds') == 33
          and d.get('silentRounds') == 1, json.dumps(d, ensure_ascii=False)[:150])

    r = req(APP + '/api/plugins/settings/subagent', 'POST',
            {'maxDepth': 2, 'maxConcurrency': 2, 'model': 'mock-model'})
    d = r.get('data') or {}
    check('子智能体插件：递归层级/并发/模型可配置',
          d.get('maxDepth') == 2 and d.get('maxConcurrency') == 2
          and d.get('model') == 'mock-model', json.dumps(d, ensure_ascii=False)[:150])

    r = req(APP + '/api/plugins/team', 'POST',
            {'name': '代码审查员', 'mode': 'MINIMAL', 'role': '只读代码、不给结论'})
    member = (r.get('data') or {})
    mid = member.get('id')
    check('智能体团队：能新增成员（模式被规范化成 MINIMAL）',
          r.get('success') is True and member.get('mode') == 'MINIMAL' and bool(mid),
          json.dumps(member, ensure_ascii=False)[:140])
    bad = req(APP + '/api/plugins/team', 'POST', {'name': 'x', 'mode': 'FAST'})
    check('智能体团队：非法模式被拒绝（不会存下一条用不了配置）',
          bad.get('success') is False, str(bad.get('error'))[:80])
    r = req(APP + '/api/plugins/team/%s' % mid, 'POST', {'role': '改过的职责'})
    check('智能体团队：能改成员且只改传进来的字段',
          (r.get('data') or {}).get('role') == '改过的职责'
          and (r.get('data') or {}).get('mode') == 'MINIMAL')
    r = req(APP + '/api/plugins/team', 'GET')
    has = any(m.get('id') == mid for m in (r.get('data') or []))
    r2 = req(APP + '/api/plugins/team/%s' % mid, 'DELETE')
    r3 = req(APP + '/api/plugins/team', 'GET')
    check('智能体团队：能列出、能删除',
          has and r2.get('success') is True
          and not any(m.get('id') == mid for m in (r3.get('data') or [])))

    req(APP + '/api/plugins/plugin.approval-review/enable', 'POST', {})
    r = req(APP + '/api/plugins/settings/review', 'POST', {'model': 'mock-model'})
    d = r.get('data') or {}
    check('自动授权审查：开启后能配审核用的模型，且知道要审哪些工具',
          d.get('enabled') is True and d.get('model') == 'mock-model'
          and len(d.get('tools') or []) > 0, json.dumps(d, ensure_ascii=False)[:150])

    bad = req(APP + '/api/plugins/automation', 'POST',
              {'prompt': '没有会话的任务', 'everySeconds': 60})
    check('自动化任务：不指定会话直接拒绝',
          bad.get('success') is False and 'sessionId' in str(bad.get('error')),
          str(bad.get('error'))[:90])
    r = req(APP + '/api/plugins/automation', 'POST',
            {'name': '每小时巡检', 'sessionId': sid, 'prompt': '看看有没有报错',
             'everySeconds': 3600})
    task = r.get('data') or {}
    tid = task.get('id')
    check('自动化任务：能按周期创建（含会话与指令）',
          r.get('success') is True and task.get('everySeconds') == 3600 and bool(tid),
          json.dumps(task, ensure_ascii=False)[:140])
    due = req(APP + '/api/plugins/automation/due')
    due_ids = [d.get('id') for d in (due.get('data') or [])]
    check('★ 自动化任务：周期任务首次到点（pollDue 判定，不起线程真跑）',
          tid in due_ids, 'due=%s' % due_ids)
    due2 = req(APP + '/api/plugins/automation/due')
    check('自动化任务：到点判定是纯查询（连查两次结果一致，不会自己改状态）',
          [d.get('id') for d in (due2.get('data') or [])] == due_ids)
    r = req(APP + '/api/plugins/automation/%s' % tid, 'DELETE')
    check('自动化任务：能删除', r.get('success') is True)

    # ---------- E 热插拔：坏 jar 不能把应用搞挂 ----------
    plugins_dir = listing['pluginsDir']
    if not os.path.isdir(plugins_dir):
        os.makedirs(plugins_dir, exist_ok=True)
    with open(os.path.join(plugins_dir, 'broken-plugin.jar'), 'wb') as f:
        f.write(b'this is definitely not a zip file, it is a plugin someone half-built')
    r = req(APP + '/api/plugins/reload', 'POST', {})
    check('POST /api/plugins/reload 正常返回 {ok,count,plugins}',
          r.get('ok') is True and 'count' in r and isinstance(r.get('plugins'), list),
          json.dumps({k: r.get(k) for k in ('ok', 'count', 'total')}, ensure_ascii=False))
    listing = req(APP + '/api/plugins')
    broken = [p for p in listing['plugins'] if p.get('broken')]
    check('★ 坏 jar 记进列表的 error 字段（界面上看得见），应用照常活着',
          listing.get('ok') is True and len(broken) >= 1
          and all(p.get('error') for p in broken),
          (broken[0].get('id') if broken else '无坏插件条目'))
    check('坏 jar 不影响别的插件（read_file 还在列表里）',
          plugin_of(listing, 'tool.file.read') is not None)
    os.remove(os.path.join(plugins_dir, 'broken-plugin.jar'))
    req(APP + '/api/plugins/reload', 'POST', {})

    # ---------- F 插件开发模式 ----------
    dm = req(APP + '/api/plugins/dev-mode')
    check('GET /api/plugins/dev-mode 默认关闭', dm.get('ok') is True and dm.get('devMode') is False)
    r = req(APP + '/api/plugins/scaffold', 'POST',
            {'id': 'demo.echo', 'name': '回声示例', 'kind': 'BASE_TOOL'})
    check('开发模式没开时 scaffold 拒绝生成（不会偷偷写文件）', r.get('ok') is False,
          str(r.get('error'))[:80])

    r = req(APP + '/api/plugins/dev-mode', 'POST', {'enabled': True})
    dm2 = req(APP + '/api/plugins/dev-mode')
    check('POST /api/plugins/dev-mode 能开启，且状态查询同步',
          r.get('ok') is True and dm2.get('devMode') is True)

    r = req(APP + '/api/plugins/scaffold', 'POST',
            {'id': 'demo.echo', 'name': '回声示例', 'kind': 'BASE_TOOL'})
    path = r.get('path') or ''
    check('scaffold 生成插件工程（返回工程路径）',
          r.get('ok') is True and path and os.path.isdir(path), path)
    want_files = ['README.md', 'build.ps1',
                  os.path.join('src', 'main', 'resources', 'META-INF', 'lionbox-plugin.properties')]
    java_files = []
    for root, _dirs, files in os.walk(path):
        for fn in files:
            if fn.endswith('.java'):
                java_files.append(os.path.join(root, fn))
    check('生成的工程文件齐全（源码 + 清单 + 构建脚本 + README）',
          len(java_files) == 1 and all(os.path.isfile(os.path.join(path, w)) for w in want_files),
          'java=%s' % [os.path.basename(f) for f in java_files])
    check('生成的 README 写清了怎么编译加载',
          'build.ps1' in open(os.path.join(path, 'README.md'), encoding='utf-8').read())

    # 能编译：javac 编到一半就报错的话，"最小插件工程"就是空话
    javac = find_jdk_tool('javac')
    jar_tool = find_jdk_tool('jar')
    sdk = r.get('sdkJar')
    compiled = False
    if javac and sdk and os.path.isfile(sdk):
        outdir = os.path.join(TMP, 'scaffold-classes')
        os.makedirs(outdir, exist_ok=True)
        cp = subrun([javac, '-encoding', 'UTF-8', '-nowarn', '-cp', sdk, '-d', outdir] + java_files)
        compiled = cp.returncode == 0
        check('★ scaffold 生成的 Java 源码是合法 Java（javac 编译通过）', compiled,
              (cp.stderr or cp.stdout or '')[-160:])
    else:
        warn('跳过 javac 编译校验（没找到 javac 或 SDK）', 'javac=%s sdk=%s' % (javac, sdk))

    # 能加载：先试工程自带的 build.ps1（它就是给用户用的那条路），失败再手动打包
    built_jar = os.path.join(plugins_dir, 'demo.echo.jar')
    ps = shutil.which('powershell') or shutil.which('pwsh')
    ps_ok = False
    if ps and os.path.isfile(os.path.join(path, 'build.ps1')):
        bp = subrun([ps, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                     '-File', os.path.join(path, 'build.ps1')], timeout=300, cwd=path)
        ps_ok = bp.returncode == 0 and os.path.isfile(built_jar)
        if not ps_ok:
            warn('build.ps1 执行失败，改用手动打包继续验证加载',
                 (bp.stderr or bp.stdout or '')[-200:])
    if not ps_ok and compiled and jar_tool:
        # 手动：把编译产物 + 资源清单打成 jar（等价于 build.ps1 干的事）
        with zipfile.ZipFile(built_jar, 'w', zipfile.ZIP_DEFLATED) as z:
            for root, _d, files in os.walk(outdir):
                for fn in files:
                    full = os.path.join(root, fn)
                    z.write(full, os.path.relpath(full, outdir))
            res = os.path.join(path, 'src', 'main', 'resources')
            for root, _d, files in os.walk(res):
                for fn in files:
                    full = os.path.join(root, fn)
                    z.write(full, os.path.relpath(full, res))
        ps_ok = os.path.isfile(built_jar)
    check('★ 生成工程能打出插件 jar（build.ps1 或等价打包）', ps_ok, built_jar)

    r = req(APP + '/api/plugins/reload', 'POST', {})
    listing = req(APP + '/api/plugins')
    ext = plugin_of(listing, 'demo.echo')
    check('★ 打出来的 jar 放进插件目录后能被加载（列表里出现 demo.echo）',
          ext is not None, json.dumps(r.get('errors'), ensure_ascii=False)[:120] if ext is None else '')
    if ext:
        check('外部插件标记正确（source=external、hotReloadable=true、builtin=false）',
              ext.get('source') == 'external' and ext.get('hotReloadable') is True
              and ext.get('builtin') is False, json.dumps(ext, ensure_ascii=False)[:160])
        check('外部插件按自己的 isAvailableInMode 归类为基础工具（BASE_TOOL）',
              ext.get('kind') == 'BASE_TOOL', ext.get('kind'))
        req(APP + '/api/plugins/demo.echo/disable', 'POST', {})
        r = req(APP + '/api/plugins/demo.echo', 'DELETE')
        after = req(APP + '/api/plugins')
        check('★ 外部插件能单独卸载（DELETE 后从列表消失）',
              r.get('success') is True and plugin_of(after, 'demo.echo') is None)
    else:
        check('外部插件标记正确（source=external、hotReloadable=true、builtin=false）', False, '未加载')
        check('外部插件按自己的 isAvailableInMode 归类为基础工具（BASE_TOOL）', False, '未加载')
        check('★ 外部插件能单独卸载（DELETE 后从列表消失）', False, '未加载')

    check('设置文件落在插件目录里（settings.json）',
          os.path.isfile(os.path.join(plugins_dir, 'settings.json')),
          os.path.join(plugins_dir, 'settings.json'))
    raw = json.load(open(os.path.join(plugins_dir, 'settings.json'), encoding='utf-8'))
    check('settings.json 里记下了用户点过的开关（tool.file.read=false）',
          (raw.get('enabled') or {}).get('tool.file.read') is False,
          json.dumps(raw.get('enabled'), ensure_ascii=False)[:120])

    # ---------- G 重启：开关必须还在 ----------
    proc.terminate()
    subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    log.close()
    time.sleep(2)
    kill_port(APP_PORT)

    proc2, log2 = start_app(APP_PORT2, LOG2)
    listing2 = wait_ready(APP2)
    check('★ 重启后关掉的插件仍然是关的（开关持久化）',
          (plugin_of(listing2, 'tool.file.read') or {}).get('enabled') is False)
    check('★ 重启后终端限制也还在（3 秒 / 700 字节）',
          (req(APP2 + '/api/plugins/settings').get('data') or {}).get('terminal', {})
          .get('maxCommandSeconds') == 3)
    check('★ 重启后开发模式仍是开的（运行时开关会持久化）',
          req(APP2 + '/api/plugins/dev-mode').get('devMode') is True)

    # ---------- H 设置文件被写坏时也不能起不来 ----------
    settings_file = os.path.join(HOME, '.lioncode', 'plugins', 'settings.json')
    req(APP2 + '/api/plugins/settings/terminal', 'POST', {'maxCommandSeconds': 45})
    subprocess.run(['taskkill', '/F', '/PID', str(proc2.pid)], capture_output=True)
    log2.close()
    proc2 = None
    time.sleep(3)
    kill_port(APP_PORT2)
    with open(settings_file, 'w', encoding='utf-8') as f:
        f.write('{ "enabled": { "tool.file.read": false },, 这不是合法 JSON')
    proc3, log3 = start_app(APP_PORT3, LOG3)
    try:
        listing3 = wait_ready(APP3)
        check('★ settings.json 被写坏时应用照常启动（按默认设置运行，不崩）',
              listing3.get('ok') is True
              and (plugin_of(listing3, 'tool.file.read') or {}).get('enabled') is True,
              'read_file.enabled=%s' % (plugin_of(listing3, 'tool.file.read') or {}).get('enabled'))
        check('坏掉的设置文件被改名留证（settings.json.broken），不是静默丢掉',
              os.path.isfile(settings_file + '.broken'))
    finally:
        subprocess.run(['taskkill', '/F', '/PID', str(proc3.pid)], capture_output=True)
        log3.close()
        time.sleep(1)
        kill_port(APP_PORT3)

finally:
    for p, lg in ((proc, log), (proc2, log2)):
        try:
            if p is not None and p.poll() is None:
                subprocess.run(['taskkill', '/F', '/PID', str(p.pid)], capture_output=True)
        except Exception:
            pass
        try:
            lg.close()
        except Exception:
            pass
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)
    kill_port(APP_PORT2)
    kill_port(APP_PORT3)
    print('已清理')

total = len(CHECKS)
passed = sum(1 for _l, ok in CHECKS if ok)
print('=' * 72)
print('结果：%d/%d 项通过 —— %s' % (passed, total, '全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)

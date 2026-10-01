# -*- coding: utf-8 -*-
r"""回归测试：标准模式 = 全部工具；极简模式 = 只有文件类 + shell 类工具。

用户原话：
  "标准模式要能调用全部工具，极简模式只能调用文件类和shell类的工具"

验四件事：
  1. /api/runtime/prompt-preview?mode=standard 的工具清单里有全部工具（网络/Git/编解码…）
  2. ...?mode=minimal 的清单里只有文件类 + shell 类，且**不出现** web_search / timestamp / git_*
  3. 极简模式的系统提示词里明写了"实际可用的工具只有这些"，并且那份名单和清单一致
  4. 真跑一轮极简模式：模型硬要调 web_search / timestamp（模式外工具）时，
     明确回"在 minimal 模式下不可用"，而不是抛异常或假装成功
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

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8892
APP_PORT = 8908
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionmode')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 极简模式该有的工具（文件类 + shell 类）
#
# skill_load 是**有意放进来**的：技能目录（SKILL.md 通用格式那套）在两个模式都会注入
# 系统提示词，提示模型"匹配到某个技能就先 skill_load 拿完整指令"。目录注入了、工具却没有，
# 模型照着提示词去调就会撞"未找到工具"。它本身只是个只读的元数据读取（读一份 md），
# 不构成"极简模式能力被放大"，所以不算越界。
MINIMAL_EXPECT = {
    'read_file', 'write_file', 'append_file', 'create_file', 'create_directory', 'delete_file',
    'move_file', 'copy_file', 'list_directory', 'directory_tree', 'file_info', 'line_count',
    'word_count', 'head_tail_file', 'change_permissions', 'modify_file', 'glob_files',
    'search_in_files', 'execute_command', 'run_background', 'stop_background',
    'skill_load',
}
# 极简模式绝不该出现的（网络 / Git / 编解码 / 系统）
MINIMAL_FORBIDDEN = {
    'web_search', 'fetch_url', 'http_get', 'http_post', 'download_file', 'dns_lookup',
    'translate', 'timestamp', 'system_info', 'get_env', 'hash', 'base64', 'json_format',
    'yaml_process', 'generate_uuid', 'regex_test', 'git_init', 'git_status', 'git_log',
    'git_commit', 'git_branch', 'git_diff', 'git_reset', 'git_stash', 'git_remote',
    'ask_user', 'diff_text', 'markdown_render', 'string_utils', 'number_convert',
    'escape_string', 'working_directory', 'format_code', 'cron_parse',
}
# 标准模式必须全都有
STANDARD_EXPECT = MINIMAL_EXPECT | MINIMAL_FORBIDDEN

ROUNDS = [
    [('read_file', {'path': 'note.txt'})],
    # 模式外工具：模型硬要调（极简模式工具清单里根本没有它们）
    [('web_search', {'query': 'lionbox'})],
    [('timestamp', {})],
]
SEEN = []
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


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
            return self._json({'seen': SEEN})
        if self.path.startswith('/__prompt'):
            return self._json({'prompt': LAST_PROMPT})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        global LAST_PROMPT
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        for m in msgs:
            if m.get('role') == 'system':
                LAST_PROMPT = str(m.get('content') or '')
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        tool_msgs = [m for m in msgs if m.get('role') == 'tool']
        if tool_msgs:
            SEEN.append([str(m.get('content') or '') for m in tool_msgs])
        done = len(tool_msgs)
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif done < len(ROUNDS):
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[done])
        else:
            content = '模式测完了。'
        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})


LAST_PROMPT = ''


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


def req(url, method='GET', body=None, timeout=600):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'replace'))


def kill_port(port):
    out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)
    deadline = time.time() + 20
    while time.time() < deadline:
        rows = subprocess.run(['netstat', '-ano', '-p', 'TCP'],
                              capture_output=True, text=True).stdout.splitlines()
        busy = any(len(x.split()) >= 5 and x.split()[1].endswith(':' + str(port))
                   and x.split()[3] == 'LISTENING' for x in rows)
        if not busy:
            return
        time.sleep(0.5)


def tool_names(mode):
    """从 prompt-preview 里把这一模式的工具清单取出来（toolDefinitions 是 JSON 字符串）"""
    r = req(APP + '/api/runtime/prompt-preview?mode=%s&full=true' % mode)
    d = r.get('data') or {}
    defs = d.get('toolDefinitions') or '[]'
    try:
        parsed = json.loads(defs)
    except Exception:
        parsed = []
    names = set()
    for item in parsed:
        if isinstance(item, dict):
            n = item.get('name') or (item.get('function') or {}).get('name')
            if n:
                names.add(n)
    return names, (d.get('systemPrompt') or '')


print('=' * 72)
print('标准模式 = 全部工具 / 极简模式 = 文件类 + shell 类')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
with open(os.path.join(WS, 'note.txt'), 'w', encoding='utf-8') as f:
    f.write('hello minimal\n')

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

log = open(LOG, 'w', encoding='utf-8', errors='replace')
proc = subprocess.Popen([real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
                         '--server.port=%d' % APP_PORT,
                         '--lionbox.runtime.auto-download=false',
                         # 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，开着审核
                         # 文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）
                         '--lionbox.change-review.enabled=false',
                         '--lionbox.runtime.prewarm.enabled=false'],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
try:
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            break
        except Exception:
            time.sleep(1)

    std, std_prompt = tool_names('standard')
    mini, mini_prompt = tool_names('minimal')

    print('  标准模式 %d 个工具；极简模式 %d 个工具' % (len(std), len(mini)))
    print('  极简模式清单: %s' % '、'.join(sorted(mini)))

    missing_std = sorted(STANDARD_EXPECT - std)
    check('★ 标准模式有全部工具（%d 个）' % len(STANDARD_EXPECT), not missing_std,
          ('缺: ' + '、'.join(missing_std)) if missing_std else '')
    check('★ 标准模式真的包含网络/Git/编解码类工具',
          {'web_search', 'git_status', 'hash', 'translate', 'timestamp'} <= std)

    extra_mini = sorted(mini - MINIMAL_EXPECT)
    check('★ 极简模式只有文件类 + shell 类工具', not extra_mini,
          ('多出: ' + '、'.join(extra_mini)) if extra_mini else '')
    bad_mini = sorted(mini & MINIMAL_FORBIDDEN)
    check('★ 极简模式不含网络/Git/系统类工具', not bad_mini,
          ('不该有: ' + '、'.join(bad_mini)) if bad_mini else '')
    check('极简模式文件工具齐全（含 glob_files/search_in_files 这类文件检索）',
          {'read_file', 'write_file', 'modify_file', 'glob_files', 'search_in_files',
           'line_count', 'word_count', 'head_tail_file'} <= mini)
    check('极简模式含 shell 工具', {'execute_command', 'run_background', 'stop_background'} <= mini)

    check('★ 极简提示词里写明了"实际可用的工具只有这些"', '只有下面这些' in mini_prompt)
    check('★ 极简提示词不再提 web_search 这类模式外工具',
          'web_search' not in mini_prompt and 'git_commit' not in mini_prompt)
    check('标准模式提示词里没有极简那段限制', '只有下面这些' not in std_prompt)

    # 真跑一轮极简模式，看模式外工具被拒时的话说得对不对
    w = req(APP + '/api/workspaces', 'POST', {'path': WS})
    ws_id = (w.get('data') or {}).get('workspaceId') or (w.get('data') or {}).get('id')
    s = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'MINIMAL'})
    sid = (s.get('data') or {}).get('sessionId')
    sess_mode = (s.get('data') or {}).get('mode')
    check('新会话确实是极简模式', str(sess_mode).lower().startswith('min'), str(sess_mode))

    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '随便读个文件，再试试联网工具',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=600)

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    rows = seen[-1] if seen else []
    print('  极简模式跑下来带回 %d 条工具结果：' % len(rows))
    for idx, content in enumerate(rows, 1):
        print('    %2d %s' % (idx, content.replace('\n', ' ')[:110]))
    joined = ' '.join(rows)
    check('★ 极简模式下 web_search 被明确挡下（说清是模式限制）',
          'web_search' in joined and '不可用' in joined)
    check('★ 极简模式下 timestamp 也被挡下', 'timestamp' in joined and '不可用' in joined)
    check('模式外工具不会抛异常（只说不可用）', 'Exception' not in joined and '错误: null' not in joined)
    check('极简模式下文件工具照常能用', 'hello minimal' in joined)
    check('任务正常收尾', '模式测完了' in str(c.get('data')), str(c.get('data'))[:60])
finally:
    log.close()
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)
    print('已清理')

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)

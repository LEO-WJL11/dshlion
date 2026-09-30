# -*- coding: utf-8 -*-
r"""工具调用准确率基准（**用真模型**：本机 llama-server 8788 上的 IQ4_XS）。

为什么要这个：用户要求"IQ4 模型在复杂任务下的工具调用准确率 ≥ 99%"。
这不是拍脑袋能说的数，得先有**可复现的量法**：

  1. 用真实链路取系统提示词（app 的 /api/runtime/prompt-preview?full=true，文本通道）；
  2. 把"复杂任务"逐条发给真 llama-server（temperature=0，可复现）；
  3. 用**和 Java 侧同一套规则**解析模型输出（QwenToolCallParser 的两种格式）；
  4. 打分：
       · 首次调用正确率 = 第一次回复就给出**对的工具 + 对的必需参数**的比例
       · 综合正确率     = 允许最多 N 轮"把错误回给模型让它改"之后正确的比例
       · 另外记 没调工具 / 编造工具名 / 参数缺失 各占多少
  5. 失败项逐条打印（工具名、参数、原始输出片段），改完再跑，直到 ≥ 99%。

用法：
    python tools/bench/_bench_toolcalls.py                # 全部任务，每轮修 2 次
    python tools/bench/_bench_toolcalls.py --only 3,7     # 只跑第 3、7 条
    python tools/bench/_bench_toolcalls.py --mode minimal # 换极简模式的提示词
"""
import argparse
import difflib
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _app import start_app, stop_app  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
LLAMA = 'http://127.0.0.1:8788/v1/chat/completions'
APP_PORT = 8919
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionbench')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# ---------------------------------------------------------------- 任务集
# 每条：复杂任务描述 + 期望的第一次工具调用（工具名 + 必需参数；None 表示不校验该参数值）。
# 复杂度体现在：要理解"动手"、要挑对工具、参数要具体（路径/行号/内容/命令），
# 而且故意混入"不能只看字面"的坑（比如"用 shell 列目录"其实是 list_directory 也行，
# 但必须给出可执行的东西）。
TASKS = [
    {"id": 1, "task": "读一下 config.txt 这个文件的内容",
     "tool": "read_file", "args": {"path": "config.txt"}},
    {"id": 2, "task": "config.txt 里前 5 行是什么？",
     "tool": "head_tail_file", "args": {"path": "config.txt"}},
    {"id": 3, "task": "统计 src 目录下所有文件一共多少行代码",
     "tool": "line_count", "args": {"path": None}},
    {"id": 4, "task": "看看当前工作区里有哪些文件和目录",
     "tool": "list_directory", "args": {"path": None}},
    {"id": 5, "task": "把整个工作区的目录结构画给我看，最多 3 层",
     "tool": "directory_tree", "args": {"path": None}},
    {"id": 6, "task": "在项目里找出所有提到 TODO 的地方，最多 20 条",
     "tool": "search_in_files", "args": {"pattern": "TODO"}},
    {"id": 7, "task": "把所有 .java 文件列出来",
     "tool": "glob_files", "args": {"pattern": None}},
    {"id": 8, "task": "新建一个文件 notes/待办.md，内容写上「今天要做完发布准备」",
     # 【注意】这里以前期望 create_file，是**期望写错了**：create_file 只建空文件、根本没有
     # content 参数，要"写上内容"只能用 write_file。（模型第一次就选了 write_file，被判成错，
     # 属于标准答案有毛病而不是模型有毛病。）
     "tool": "write_file", "args": {"path": None, "content": None}},
    {"id": 9, "task": "往 notes/待办.md 末尾追加一行「- 压测」",
     "tool": "append_file", "args": {"path": None}},
    {"id": 10, "task": "把 a.txt 里的 hello 全部替换成你好",
     "tool": "modify_file", "args": {"path": "a.txt"}},
    {"id": 11, "task": "把 a.txt 的第 3 到第 5 行替换成一行 done",
     "tool": "modify_file", "args": {"path": "a.txt"}},
    {"id": 12, "task": "把 old.txt 改名成 new.txt",
     "tool": "move_file", "args": {"source": None, "destination": None}},
    {"id": 13, "task": "把 a.txt 复制一份叫 a.bak.txt",
     "tool": "copy_file", "args": {"source": None, "destination": None}},
    {"id": 14, "task": "删掉临时目录 tmp_out",
     "tool": "delete_file", "args": {"path": None}},
    {"id": 15, "task": "在终端里跑一下 git status 看当前仓库状态",
     "tool": "execute_command", "args": {"command": "git status"}},
    {"id": 16, "task": "跑一下项目测试：mvn -o -q -DskipTests package",
     "tool": "execute_command", "args": {"command": None}},
    {"id": 17, "task": "后台启动一个每 5 秒打一次时间戳的进程",
     "tool": "run_background", "args": {"command": None}},
    {"id": 18, "task": "初始化一个 git 仓库",
     "tool": "git_init", "args": {"path": None}},
    {"id": 19, "task": "给当前改动写一条提交信息叫「发布准备」并提交",
     "tool": "git_commit", "args": {"message": None}},
    {"id": 20, "task": "算一下这个文件的 sha256：a.txt",
     "tool": "hash", "args": {"input": None}},
    {"id": 21, "task": "把这段 base64 YWJj 解出来",
     "tool": "base64", "args": {"input": None}},
    {"id": 22, "task": "把 {\"a\":1,\"b\":[2,3]} 格式化一下",
     "tool": "json_format", "args": {"input": None}},
    {"id": 23, "task": "把「你好，世界」翻译成英文",
     "tool": "translate", "args": {"text": None}},
    {"id": 24, "task": "搜一下 Node.js 最新 LTS 版本是多少",
     "tool": "web_search", "args": {"query": None}},
    {"id": 25, "task": "当前系统是什么 CPU、多少内存",
     "tool": "system_info", "args": {}},
    {"id": 26, "task": "现在几点？",
     "tool": "timestamp", "args": {}},
    {"id": 27, "task": "生成 3 个 uuid",
     "tool": "generate_uuid", "args": {"count": None}},
    {"id": 28, "task": "这个环境里 PATH 是什么",
     "tool": "get_env", "args": {"name": None}},
    {"id": 29, "task": "查一下 example.com 解析到哪个 IP",
     "tool": "dns_lookup", "args": {"domain": None}},
    {"id": 30, "task": "把 https://example.com 这个页面抓下来看看",
     "tool": "fetch_url", "args": {"url": None}},
]

# ---------------------------------------------------------------- 解析（与 Java 侧同规则）
FUNCTION = re.compile(r'<function\s*=\s*([A-Za-z_][\w.\-]*)\s*>(.*?)</function>', re.S)
PARAMETER = re.compile(r'<parameter\s*=\s*([A-Za-z_][\w.\-]*)\s*>(.*?)</parameter>', re.S)
# 提示词里工具清单的一行（两种形态都要认）：
#   - read_file(offset, encoding, limit, path*): 说明
#   - system_info: 获取系统运行时信息          ← 无参工具没有括号！
# 第一版正则强制要求括号，结果 system_info 被判成"参数不齐"，合法率白白掉 3 个点。
TOOL_LINE = re.compile(r'^- ([a-z0-9_]+)(?:\(([^)]*)\))?:', re.M)


def parse_calls(text):
    """同 QwenToolCallParser：<function=name><parameter=k>v</parameter></function>（可带/不带外壳）"""
    out = []
    if not text:
        return out
    for m in FUNCTION.finditer(text):
        args = {}
        for pm in PARAMETER.finditer(m.group(2)):
            args[pm.group(1).strip()] = pm.group(2).strip()
        if not args:
            # JSON 兜底
            body = m.group(2)
            b, e = body.find('{'), body.rfind('}')
            if b >= 0 and e > b:
                try:
                    args = json.loads(body[b:e + 1])
                except Exception:
                    args = {}
        out.append((m.group(1).strip(), args))
    return out


def normalize(v):
    """参数值比较：去掉首尾空白/引号、路径分隔符统一、全角空格，转小写（路径不区分大小写）"""
    if v is None:
        return None
    s = str(v).strip().strip('"').strip("'").strip()
    s = s.replace('\\', '/').replace('\u3000', ' ')
    while '//' in s:
        s = s.replace('//', '/')
    if s.startswith('./'):
        s = s[2:]
    return s


def args_ok(expected, got):
    """期望的每个参数都要对上（None = 只要给了这个键就算对）"""
    for k, want in expected.items():
        if k not in got:
            # 允许别名：source/target、path/file 之类
            alias = {'source': ['src', 'from', 'old', 'oldPath', 'source_path'],
                     'destination': ['dest', 'to', 'new', 'newPath', 'target', 'destination_path'],
                     'path': ['file', 'filepath', 'file_path', 'filename', 'name'],
                     'input': ['data', 'text', 'content', 'value'],
                     'text': ['input', 'content', 'data'],
                     'pattern': ['query', 'keyword', 'regex'],
                     'query': ['pattern', 'keyword', 'q'],
                     'command': ['cmd', 'shell', 'command_line'],
                     'name': ['key', 'var', 'env'],
                     'domain': ['host', 'name', 'url'],
                     'url': ['link', 'address'],
                     'message': ['msg', 'commit_message']}
            hit = None
            for a in alias.get(k, []):
                if a in got:
                    hit = a
                    break
            if hit is None:
                return False, '缺少参数 %s' % k
            got[k] = got[hit]
        if want is not None and normalize(got[k]) != normalize(want):
            return False, '参数 %s = %r，期望 %r' % (k, got[k], want)
    return True, ''


# ---------------------------------------------------------------- 应用（取真实提示词）
class Mock(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def do_GET(self):
        body = json.dumps({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        self.rfile.read(n)
        body = json.dumps({'id': 'x', 'object': 'chat.completion', 'created': 0, 'model': 'mock',
                           'choices': [{'index': 0, 'finish_reason': 'stop',
                                        'message': {'role': 'assistant', 'content': 'ok'}}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


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
    return shutil.which('java') or 'java'


def kill_port(port):
    out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)
    time.sleep(1)


def llm(system, messages, max_tokens=512):
    body = {"model": "lion-models1", "temperature": 0, "top_p": 1, "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system}] + messages}
    req_ = urllib.request.Request(LLAMA, data=json.dumps(body).encode('utf-8'),
                                  headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req_, timeout=600) as r:
        res = json.loads(r.read().decode('utf-8', 'replace'))
    ch = res['choices'][0]
    return ch['message'].get('content') or '', ch.get('finish_reason')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only', default='')
    ap.add_argument('--mode', default='standard')
    ap.add_argument('--repairs', type=int, default=2)
    ap.add_argument('--max-tokens', type=int, default=512)
    args = ap.parse_args()

    only = [int(x) for x in args.only.split(',') if x.strip()]
    tasks = [t for t in TASKS if not only or t['id'] in only]

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
    srv = ThreadingHTTPServer(('127.0.0.1', 8896), Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
        json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
                   'baseUrl': 'http://127.0.0.1:8896/v1', 'apiKey': 'sk-mock',
                   'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

    # 走 _app.start_app：它会把 fat jar 复制一份私有副本再跑（否则 target 里的 jar 被锁住，
    # 别人的 mvn package 会把 fat jar 打回瘦 jar，整个仓库的测试一起挂）
    proc, log = start_app(APP_PORT, HOME, log_path=LOG)
    try:
        for _ in range(180):
            try:
                req(APP + '/api/runtime/mode', timeout=3)
                break
            except Exception:
                time.sleep(1)

        first_ok = 0
        any_ok = 0
        legal_ok = 0
        no_call = 0
        wrong_name = 0
        bad_args = 0
        detail_fail = []
        illegal_detail = []

        print('=' * 78)
        print('工具调用准确率基准：%d 条复杂任务（真 IQ4 模型，temperature=0，模式=%s）'
              % (len(tasks), args.mode))
        print('=' * 78)
        t0 = time.time()
        for t in tasks:
            pv = req('%s/api/runtime/prompt-preview?mode=%s&message=%s&workspace=%s&full=true'
                     % (APP, args.mode, urllib.parse.quote(t['task']), urllib.parse.quote(WS)))
            system = (pv.get('data') or {}).get('systemPrompt') or ''
            # 工具清单从**模型真正看到的那份提示词**里抠，保证"合法"判定和线上一致
            spec = {}
            for m in TOOL_LINE.finditer(system):
                params = m.group(2) or ''   # 无参工具没有括号，group(2) 是 None
                spec[m.group(1)] = {a.strip().rstrip('*') for a in params.split(',')
                                    if a.strip().endswith('*')}
            messages = [{"role": "user", "content": t['task']}]
            ok_this = False
            first_try_ok = False
            legal_this = False
            tried = []
            for attempt in range(args.repairs + 1):
                text, fin = llm(system, messages, args.max_tokens)
                calls = parse_calls(text)
                tried.append((text, calls, fin))
                if not calls:
                    if attempt == 0:
                        no_call += 1
                    # 提醒它必须动手
                    messages = messages + [
                        {"role": "assistant", "content": text},
                        {"role": "user", "content":
                         "你没有调用工具。如果需要动手，请只回一个工具调用块："
                         "<tool_call><function=工具名><parameter=参数名>值</parameter></function></tool_call>"}]
                    continue
                name, got = calls[0]
                if attempt == 0:
                    missing = [a for a in spec.get(name, ()) if a not in got]
                    legal_this = name in spec and not missing
                    if not legal_this:
                        illegal_detail.append((t['id'], name, missing))
                good, why = (name == t['tool']), ''
                if good:
                    good, why = args_ok(t['args'], got)
                    if not good:
                        if attempt == 0:
                            bad_args += 1
                elif attempt == 0:
                    wrong_name += 1
                if good:
                    ok_this = True
                    if attempt == 0:
                        first_try_ok = True
                    break
                # 这里只模拟**应用能拦下来**的错误，给模型的提示也只给应用会给的那些
                # （未找到工具 + 最接近的几个名字 / 缺哪个必需参数）。
                # 【重要】以前这里直接把"应该用 head_tail_file"告诉模型，那等于把答案喂给它，
                # 量出来的"综合正确率"是假的。工具名合法但选错（比如该 head 却用了
                # list_directory）应用是照跑的，模型只能靠看工具结果自己回过味来 ——
                # 那种情况这里不算，交给 _bench_e2e.py 走真实链路量。
                if name == t['tool']:
                    # 工具对、参数错：应用会回一条"缺必需参数"的错，模型有机会改
                    hint = ('工具 %s 调用失败，缺少必需参数。它需要的参数是 %s。'
                            % (t['tool'], json.dumps(t['args'], ensure_ascii=False)))
                elif name not in spec:
                    close = difflib.get_close_matches(name, list(spec), n=3, cutoff=0.5)
                    hint = '未找到工具: %s。你是不是想用这些之一：%s' % (name, '、'.join(close) or '（可用工具见提示词清单）')
                else:
                    # 合法工具、只是选错了：应用不会拒绝，也没法提示 —— 只能记下来
                    break
                messages = messages + [
                    {"role": "assistant", "content": text},
                    {"role": "user", "content": hint + ' 请重新只回一个正确的工具调用块。'}]
            if first_try_ok:
                first_ok += 1
            if legal_this:
                legal_ok += 1
            if ok_this:
                any_ok += 1
                mark = '✓' if first_try_ok else '✓(修%ss后)' % 'N'
            else:
                mark = '✗'
                detail_fail.append(t)
            print('  %2d %s %-46s → %s' % (t['id'], mark, t['task'][:46],
                                           (parse_calls(tried[0][0])[0][0] if parse_calls(tried[0][0]) else '（没调工具）')))
            if not ok_this:
                print('        第一次原文: %s' % tried[0][0].replace('\n', ' ')[:200])

        n = len(tasks)
        print()
        print('=' * 78)
        print('合法率（解析成功+工具存在+必需参数齐）: %d/%d = %.1f%%   ← harness 负责的部分'
              % (legal_ok, n, 100.0 * legal_ok / n))
        print('首次选对率（第一次就挑对工具）        : %d/%d = %.1f%%   ← 模型+提示词的能力上限'
              % (first_ok, n, 100.0 * first_ok / n))
        print('自愈率（应用能拦下的错误里改对了的）  : %d/%d = %.1f%%  （允许 %d 轮修正）'
              % (any_ok, n, 100.0 * any_ok / n, args.repairs))
        print('没调工具 %d 次 / 工具名错 %d 次 / 参数错 %d 次（都按"第一次回复"计）'
              % (no_call, wrong_name, bad_args))
        print('用时 %.0f 秒' % (time.time() - t0))
        if illegal_detail:
            print()
            print('不合法的首次调用（必须修）：')
            for tid, nm, missing in illegal_detail:
                print('  #%d 工具=%s 缺参数=%s' % (tid, nm, missing))
        if detail_fail:
            print()
            print('没选对的任务：')
            for t in detail_fail:
                print('  #%d %s' % (t['id'], t['task']))
        print('=' * 78)
    finally:
        log.close()
        if proc.poll() is None:
            subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
        srv.shutdown()
        kill_port(APP_PORT)


if __name__ == '__main__':
    import urllib.parse
    main()

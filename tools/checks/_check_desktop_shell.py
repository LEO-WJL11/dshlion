#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""桌面壳（Tauri 套壳）功能回归：它到底有没有用系统 WebView 把我们的页面加载起来。

【为什么这么验】"装完能跑"这种话不能靠感觉。这里起一个**记录请求的假 WebUI**，
把桌面壳指向它，然后看：
  1. 壳有没有真的来取页面（GET /）；
  2. 取页面的 User-Agent 是不是 Edge/WebView2（证明用的是系统 WebView，不是自带浏览器）；
  3. 壳进程有没有活着（没崩）；
  4. 页面里的 JS 有没有跑起来（让假页面回调一个 /ping，能收到就说明 JS 真的在执行）。
这样"套壳"这件事是被端到端证明的，不是"进程还在"这种糊弄判断。

用法：python tools/checks/_check_desktop_shell.py [exe路径]
默认 exe：desktop\\tauri\\src-tauri\\target\\release\\lionbox-desktop.exe
"""

import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_EXE = os.path.join(ROOT, 'desktop', 'tauri', 'src-tauri', 'target', 'release',
                           'lionbox-desktop.exe')
PORT = 8935
HITS = []

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>LionBox 假界面</title></head>
<body><h1>假 WebUI</h1>
<script>
// 页面里跑一段 JS 回调：能收到就说明 WebView 真的在执行脚本（不是只取了个 HTML）
fetch('/ping?t=' + Date.now()).catch(function(){});
</script></body></html>"""


class Mock(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        HITS.append({'path': self.path, 'ua': self.headers.get('User-Agent') or '',
                     't': time.time()})
        if self.path.startswith('/ping'):
            body = b'ok'
            ctype = 'text/plain'
        elif self.path.startswith('/api/'):
            body = b'{}'          # 后端接口随便回个空 JSON，壳只关心页面
            ctype = 'application/json'
        else:
            body = PAGE.encode('utf-8')
            ctype = 'text/html; charset=utf-8'
        self.send_response(200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_EXE
    failed = []

    def check(label, ok, detail=''):
        print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
        if not ok:
            failed.append(label)

    check('桌面壳 exe 在（Tauri 编出来的）', os.path.isfile(exe),
          '%s 字节' % format(os.path.getsize(exe), ',') if os.path.isfile(exe) else exe)
    if not os.path.isfile(exe):
        return 1

    srv = ThreadingHTTPServer(('127.0.0.1', PORT), Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    env = dict(os.environ)
    env['LIONBOX_URL'] = 'http://127.0.0.1:%d' % PORT
    proc = subprocess.Popen([exe], env=env)
    try:
        got_page = False
        for _ in range(90):
            time.sleep(1)
            if any(h['path'] == '/' for h in HITS):
                got_page = True
                break
        check('壳来取页面了（说明它真的把窗口导航到了我们的 WebUI）', got_page,
              '收到 %d 个请求' % len(HITS))
        time.sleep(4)   # 给页面里的 JS 一点时间回调 /ping
        page_hit = next((h for h in HITS if h['path'] == '/'), None)
        check('取页面用的是系统 WebView（UA 里有 Edg/WebView2）',
              bool(page_hit) and ('Edg' in page_hit['ua'] or 'WebView' in page_hit['ua']),
              (page_hit or {}).get('ua', '')[:120])
        check('页面里的 JS 真的执行了（收到了 /ping 回调）',
              any(h['path'].startswith('/ping') for h in HITS))
        check('壳进程还活着（没崩）', proc.poll() is None, 'exit=%s' % proc.poll())
    finally:
        if proc.poll() is None:
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        srv.shutdown()

    print()
    print('结果：%s' % ('全部通过' if not failed else '失败 %d 项：%s' % (len(failed), failed)))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

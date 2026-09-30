#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""提示词体检：起一个隔离实例，把某个模式真正会发出去的提示词拉下来，
在关键字前后打印上下文。排查"提示词里怎么冒出了模式外的工具名"这类问题用。

用法：
    python tools/dev/_probe_prompt.py web_search git_commit --mode minimal
    python tools/dev/_probe_prompt.py 别选错工具 --mode standard --bare
"""

import argparse
import os
import shutil
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, 'tools', 'bench'))
import _app  # noqa: E402

PORT = 8927

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('keywords', nargs='+')
    ap.add_argument('--mode', default='minimal')
    ap.add_argument('--port', type=int, default=PORT)
    ap.add_argument('--bare', action='store_true',
                    help='不带 message 参数（复现套件 tools/checks 的调用方式）')
    args = ap.parse_args()

    tmp = os.path.join(os.environ['TEMP'], '_lionprobe')
    home = os.path.join(tmp, 'home')
    ws = os.path.join(tmp, 'ws')
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(os.path.join(home, '.lioncode'), exist_ok=True)
    os.makedirs(ws, exist_ok=True)
    _app.write_config(home, {'providerMode': 'custom', 'baseUrl': 'http://127.0.0.1:8899/v1',
                             'apiKey': 'x', 'model': 'm', 'toolCallMode': 'text'})
    _app.kill_port(args.port)
    proc, log = _app.start_app(args.port, home, log_path=os.path.join(tmp, 'app.log'))
    try:
        if not _app.wait_port(args.port):
            raise SystemExit('应用起不来，看 %s' % os.path.join(tmp, 'app.log'))
        tail = '&full=true' if args.bare else '&message=%E6%B5%8B%E8%AF%95&full=true'
        d = _app.req('http://127.0.0.1:%d/api/runtime/prompt-preview?mode=%s%s'
                     % (args.port, args.mode, tail), timeout=60)
        p = (d.get('data') or {}).get('systemPrompt') or ''
        print('模式 %s，提示词 %d 字符（bare=%s）' % (args.mode, len(p), args.bare))
        for kw in args.keywords:
            idx = p.find(kw)
            if idx < 0:
                print('  [没出现] %s' % kw)
            else:
                ctx = p[max(0, idx - 140):idx + 60].replace('\n', ' / ')
                print('  [出现] %s  <- ...%s...' % (kw, ctx))
    finally:
        _app.stop_app(proc, log)
        _app.kill_port(args.port)


if __name__ == '__main__':
    main()

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""插件系统实况体检：起一个隔离实例，把注册表、开关、开发模式、四类"最小实现"插件的
真实行为全都打出来。用来对着用户的要求逐条核对，而不是看代码里"有这个名字"就算数。

用法：python tools/dev/_probe_plugins.py
"""

import json
import os
import shutil
import sys
import time
import urllib.request

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, 'tools', 'bench'))
import _app  # noqa: E402

PORT = 8935
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionpluginprobe')
HOME = os.path.join(TMP, 'home')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def get(path, timeout=30):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (PORT, path), timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def post(path, body=None, timeout=60):
    data = json.dumps(body or {}).encode('utf-8')
    req = urllib.request.Request('http://127.0.0.1:%d%s' % (PORT, path), data=data,
                                 headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def main():
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
    _app.write_config(HOME, {'providerMode': 'custom', 'baseUrl': 'http://127.0.0.1:8899/v1',
                             'apiKey': 'x', 'model': 'm', 'toolCallMode': 'text'})
    _app.kill_port(PORT)
    proc, log = _app.start_app(PORT, HOME, log_path=os.path.join(TMP, 'app.log'))
    try:
        if not _app.wait_port(PORT):
            raise SystemExit('应用起不来：%s' % os.path.join(TMP, 'app.log'))

        pl = get('/api/plugins')
        data = pl.get('data') if isinstance(pl.get('data'), dict) else pl
        plugins = data.get('plugins') or pl.get('plugins') or []
        kinds = data.get('kinds') or pl.get('kinds') or []
        print('=' * 92)
        print('插件总数 %d；后端报的类别：%s' % (len(plugins), json.dumps(kinds, ensure_ascii=False)))
        print('=' * 92)
        by_kind = {}
        for p in plugins:
            by_kind.setdefault(str(p.get('kind') or '?'), []).append(p)
        for k in sorted(by_kind):
            items = by_kind[k]
            print('\n### %s（%d 个）' % (k, len(items)))
            for p in items[:12]:
                print('   %-42s enabled=%-5s builtin=%-5s hot=%-5s err=%s'
                      % (p.get('id'), p.get('enabled'), p.get('builtin'),
                         p.get('hotReloadable'), (p.get('error') or '')[:40]))
            if len(items) > 12:
                print('   …还有 %d 个' % (len(items) - 12))

        print('\n' + '=' * 92)
        print('设置区（终端 / 大循环 / 子智能体 / 审查）')
        print('=' * 92)
        for path in ('/api/plugins/settings', '/api/plugins/dev-mode'):
            try:
                print('%-28s -> %s' % (path, json.dumps(get(path), ensure_ascii=False)[:400]))
            except Exception as e:
                print('%-28s -> 失败 %s' % (path, e))

        print('\n' + '=' * 92)
        print('团队 / 自动化 配置端点')
        print('=' * 92)
        for path in ('/api/plugins/team', '/api/plugins/automation', '/api/plugins/automation/due'):
            try:
                print('%-32s -> %s' % (path, json.dumps(get(path), ensure_ascii=False)[:300]))
            except Exception as e:
                print('%-32s -> 失败 %s' % (path, e))

        print('\n' + '=' * 92)
        print('四类"最小实现"插件的工具在不在提示词里')
        print('=' * 92)
        pv = get('/api/runtime/prompt-preview?mode=standard&full=true', timeout=60)
        sp = (pv.get('data') or {}).get('systemPrompt') or ''
        for tool in ('agent_spawn', 'agent_team_run', 'skill_load', 'execute_command',
                     'run_background', 'automation'):
            print('   %-16s %s' % (tool, '在清单里' if ('- ' + tool + '(') in sp or
                                   ('- ' + tool + ':') in sp else '不在清单里'))
    finally:
        _app.stop_app(proc, log)
        _app.kill_port(PORT)


if __name__ == '__main__':
    main()

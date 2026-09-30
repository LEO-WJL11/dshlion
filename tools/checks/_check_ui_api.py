# -*- coding: utf-8 -*-
"""静态核对：前端 web/index.html 调用的 /api/... 端点，后端是否真的存在。

这类错配在本地跑时很容易漏掉（界面点了没反应、控制台报 404，但没人看控制台），
所以拿脚本对一遍：前端 fetch 出来的路径 vs 控制器里的 @RequestMapping/@GetMapping/@PostMapping。

跑法: python _check_ui_api.py
"""
import io
import os
import re
import sys

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UI = os.path.join(ROOT, 'web', 'index.html')
JAVA_ROOT = os.path.join(ROOT, 'src', 'main', 'java')

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ui_src = io.open(UI, encoding='utf-8').read()

# 前端：fetch('...') / fetch("...") / axios 之类的字符串里的 /api 路径
ui_paths = set()
ui_prefixes = set()
for m in re.finditer(r"""fetch\(\s*['"]([^'"]+)['"]""", ui_src):
    p = m.group(1).split('?')[0]
    if not p.startswith('/api'):
        continue
    if p.endswith('/'):
        # '/api/chat/control/' + action 这种：当前缀看，别当成一个具体路径
        ui_prefixes.add(p)
    else:
        ui_paths.add(p)
# 模板拼接的（'/api/xxx/' + id 这种）当"前缀"处理，别把结尾斜杠抹掉
for m in re.finditer(r"""['"](/api/[A-Za-z0-9_\-/]*/)['"]\s*\+""", ui_src):
    ui_prefixes.add(m.group(1))

# 后端：类级 @RequestMapping + 方法级 @GetMapping/@PostMapping/...
routes = {}
for dirpath, _dirs, files in os.walk(JAVA_ROOT):
    for f in files:
        if not f.endswith('.java'):
            continue
        path = os.path.join(dirpath, f)
        src = io.open(path, encoding='utf-8', errors='replace').read()
        base = ''
        mb = re.search(r'@RequestMapping\(\s*"([^"]+)"', src)
        if mb:
            base = mb.group(1)
            if not base.startswith('/'):
                base = '/' + base
        # 注意：@GetMapping 后面可以没有括号，所以括号是可选的
        for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\b\s*(?:\(\s*(?:value\s*=\s*)?(?:"([^"]*)")?)?', src):
            sub = m.group(2) or ''
            full = (base + sub) if sub else base
            routes.setdefault(full, set()).add(f + ':' + m.group(1).upper())

print('前端引用的 /api 路径: %d 个（另有 %d 个是拼 id 的前缀），后端注册的路由: %d 个'
      % (len(ui_paths), len(ui_prefixes), len(routes)))
print()

missing = []
for p in sorted(ui_paths):
    cands = [p]
    if p.endswith('/{id}'):
        cands.append(p[:-5])
    ok = False
    for c in cands:
        if c in routes:
            ok = True
            break
        pat = re.sub(r'\{[^}]+\}', '[^/]+', c)
        if any(re.fullmatch(pat, r) for r in routes):
            ok = True
            break
        if any(re.fullmatch(re.sub(r'\{[^}]+\}', '[^/]+', r), c) for r in routes):
            ok = True
            break
    if not ok:
        missing.append(p)

# 前缀形式的：只要有任一路由以它开头就算匹配
for pref in sorted(ui_prefixes):
    if not any(r.startswith(pref) for r in routes):
        missing.append(pref + '<拼接>')

if missing:
    print('❌ 前端在用、但后端找不到对应路由的路径：')
    for p in missing:
        print('   ', p)
else:
    print('✅ 前端调用的每个 /api 路径都能在后端找到对应路由')

print()
print('后端全部路由（供对照）：')
for r in sorted(routes):
    print('   %-42s %s' % (r, ','.join(sorted(routes[r]))))
sys.exit(1 if missing else 0)

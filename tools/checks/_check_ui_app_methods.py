# -*- coding: utf-8 -*-
"""静态检查：web/index.html 里调用的 App.xxx() 是否真的定义过。

抓的就是这类 bug：调用了不存在的函数（例如 App.loadModels()），
异常被 Promise 的 .catch 吞掉，界面上显示成"下载失败/加载失败: TypeError"，
而实际动作是成功的 —— 用户看到红字以为坏了，排查半天发现是前端少了个函数。

跑法: python _check_ui_app_methods.py
"""
import io
import os
import re
import sys

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UI = os.path.join(ROOT, 'web', 'index.html')
sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

src = io.open(UI, encoding='utf-8').read()

# 先把整行注释去掉，否则"注释里提到某个旧函数名"会被误报
code_lines = []
for ln in src.split('\n'):
    s = ln.strip()
    if s.startswith('//') or s.startswith('/*') or s.startswith('*') or s.startswith('<!--'):
        code_lines.append('')
    else:
        code_lines.append(ln)
scan = '\n'.join(code_lines)
scan_to_src = None   # 行列号一致，注释行长度不变，行号可以直接用

# 1) App 上定义了哪些方法：形如  "    name: function("
defined = set(re.findall(r'^\s{4}([A-Za-z_$][\w$]*)\s*:\s*function\s*\(', scan, re.M))
# 也支持 name: (a) => 这种写法
defined |= set(re.findall(r'^\s{4}([A-Za-z_$][\w$]*)\s*:\s*\(', scan, re.M))

# 2) 哪些被调用
called = {}
for m in re.finditer(r'\b(?:App|self)\.([A-Za-z_$][\w$]*)\s*\(', scan):
    name = m.group(1)
    line = scan[:m.start()].count('\n') + 1
    called.setdefault(name, []).append(line)
# onclick="App.x()" 这类也一起看
for m in re.finditer(r'onclick="App\.([A-Za-z_$][\w$]*)\s*\(', scan):
    called.setdefault(m.group(1), []).append(scan[:m.start()].count('\n') + 1)

print('App 上定义的方法: %d 个' % len(defined))
print('被调用到的名字  : %d 个' % len(called))
print()

missing = sorted(n for n in called if n not in defined)
if missing:
    print('❌ 调用了但没定义的名字：')
    for n in missing:
        print('   %-24s 出现在第 %s 行' % (n, ', '.join(str(x) for x in called[n][:8])))
else:
    print('✅ 所有 App.xxx() 调用都能找到定义')

# 反向提示：定义了但从没被调用（往往是被改名后遗留的死代码）
unused = sorted(n for n in defined if n not in called)
if unused:
    print()
    print('（提示）定义了但没被调用: %s' % ', '.join(unused[:20]))

sys.exit(1 if missing else 0)

# -*- coding: utf-8 -*-
r"""看抓包日志里有没有工具执行失败（尤其是 ClassCastException 那一类）。"""
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
log = io.open(os.path.join(os.environ['TEMP'], '_proxy_raw.log'), encoding='utf-8', errors='replace').read()

# 请求体里有完整的 messages（含 role=tool 的结果），逐个 JSON 解出来更准
reqs = 0
results = []
for m in re.finditer(r'---- 请求体全文（\d+ 字节）----\n(.*?)\n={78}', log, re.S):
    reqs += 1
    try:
        data = json.loads(m.group(1).strip())
    except Exception:
        continue
    for msg in (data.get('messages') or []):
        if msg.get('role') == 'tool':
            results.append((msg.get('name') or msg.get('toolName') or '?', str(msg.get('content') or '')))

# 同一批结果会在后续每个请求里重复出现，按 (名字, 内容前 60 字) 去重
uniq = {}
for name, content in results:
    uniq[(name, content[:60])] = content

print('抓到 %d 个请求，工具结果去重后 %d 条' % (reqs, len(uniq)))
fails = [(n, c) for (n, _), c in uniq.items() if '工具执行错误' in c or 'Exception' in c or '失败:' in c]
print('失败 %d 条' % len(fails))
for n, c in fails[:10]:
    print('  [%s] %s' % (n, c.replace('\n', ' ')[:130]))
if not fails:
    print('  一条失败都没有 ✓')
oks = [(n, c) for (n, _), c in uniq.items() if n != '?']
print('成功样本（前 6 条）:')
for n, c in oks[:6]:
    print('  [%s] %s' % (n, c.replace('\n', ' ')[:100]))

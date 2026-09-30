# -*- coding: utf-8 -*-
r"""探针：直接问本地 llama-server（8788）—— 它的 chat 模板认不认 tools？返回什么形状？

决定"工具调用准确率"该怎么量：如果原生 tools 通道可用且稳，就走原生 + 校验；
如果模板把 tools 揉坏，就得靠文本通道 + 更严的提示词/语法约束。
"""
import json
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
BASE = 'http://127.0.0.1:8788/v1/chat/completions'

TOOLS = [
    {"type": "function", "function": {
        "name": "read_file",
        "description": "读取文件内容",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "文件路径"}},
            "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "execute_command",
        "description": "在常驻终端里执行命令",
        "parameters": {"type": "object", "properties": {
            "command": {"type": "string", "description": "要执行的命令"}},
            "required": ["command"]}}},
]


def ask(messages, tools=None, extra=None, model='lion-models1'):
    body = {"model": model, "messages": messages, "temperature": 0, "max_tokens": 200}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    if extra:
        body.update(extra)
    req = urllib.request.Request(BASE, data=json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


print('=== 1) 原生 tools 通道：让它读 config.txt 前 5 行')
res = ask([{"role": "system", "content": "你是编程助手，需要动手时直接调用工具。"},
           {"role": "user", "content": "看看 config.txt 里前 5 行是什么"}], TOOLS)
msg = res['choices'][0]['message']
print('finish_reason =', res['choices'][0].get('finish_reason'))
print('content =', repr(msg.get('content'))[:300])
print('tool_calls =', json.dumps(msg.get('tool_calls'), ensure_ascii=False)[:500])

print()
print('=== 2) 同一句话，不带 tools（看纯文本时它怎么写）')
res2 = ask([{"role": "system", "content": "你是编程助手，需要动手时直接调用工具。"},
            {"role": "user", "content": "看看 config.txt 里前 5 行是什么"}])
print('content =', repr(res2['choices'][0]['message'].get('content'))[:600])

print()
print('=== 3) 看服务端信息（模型名/模板/是否支持 tools）')
try:
    with urllib.request.urlopen('http://127.0.0.1:8788/v1/models', timeout=10) as r:
        print(r.read().decode('utf-8', 'replace')[:400])
except Exception as e:
    print('拿不到 /v1/models:', e)

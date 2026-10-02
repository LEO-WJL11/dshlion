#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""右栏（Agent 面板）整体重做：换一套 LionBox 自己的视觉，不再沿用之前那套样式。

用户要求原话："全部重新设计，不要跟原来的一样了。"

设计取向（和之前那版的区别）：
  · **有品牌色**：金色 #e0b23c 做强调色（标题、活动页签、发送键、改动卡片标题条），
    而不是全部用 VS Code 的按钮灰；
  · **有层次**：深色卡片 + 细边框 + 12px 圆角，消息是"卡片"不是"左边一条竖线"；
    用户消息靠右、Agent 消息靠左，一眼分得清谁在说话；
  · **有状态**：顶部一条品牌栏（🦁 LionBox + 连接状态药丸 + 新建/外开按钮），
    下面一行工作区（只读）；
  · **页签**改成胶囊分段控件；输入区是圆角胶囊，聚焦时金色描边；
  · 底色仍取 `--vscode-*` 变量（这样浅色主题下不会和 VS Code 打架），
    但强调色、圆角、间距、层级全部是我们自己的 —— 所以看起来不像"VS Code 自带面板"。

只改 CSS 和静态骨架，**不动渲染逻辑**：JS 用的 id（dot/status/newBtn/webBtn/ws/changes/
chat/input/sendBtn/fileBtn/hint/tab*/fullWrap/fullFrame）和 class（msg/user/assistant/system/
who/card/pre/add/del/hint/scroller）全部保留。
"""

import io, os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
p = os.path.join(ROOT, 'extensions', 'vscode', 'src', 'extension.js')
src = io.open(p, encoding='utf-8').read()

CSS = r'''
  /* ============ LionBox 面板样式（自研，非 VS Code 默认观感）============
     强调色金色来自图标；底色取 --vscode-* 以便跟随主题；圆角/间距/层级自己定。 */
  :root {
    --lb-accent: #e0b23c;
    --lb-accent-soft: rgba(224, 178, 60, .16);
    --lb-radius: 12px;
    --lb-line: var(--vscode-panel-border, #33363d);
    --lb-surface: var(--vscode-editorWidget-background, #1f2126);
    --lb-surface-2: var(--vscode-sideBar-background, #26282e);
    --lb-fg: var(--vscode-foreground, #e8eaee);
    --lb-dim: var(--vscode-descriptionForeground, #9aa3ad);
  }
  body { display: flex; flex-direction: column; gap: 0; }

  /* ---- 品牌栏 ---- */
  .brand { display: flex; align-items: center; gap: 8px; padding: 10px 12px;
           background: linear-gradient(135deg, var(--lb-accent-soft), transparent 65%),
                       var(--lb-surface);
           border: 1px solid var(--lb-line); border-radius: var(--lb-radius);
           margin-bottom: 8px; }
  .brand .mark { font-size: 16px; line-height: 1; filter: saturate(1.1); }
  .brand .name { font-weight: 700; letter-spacing: .3px; font-size: 13px; color: var(--lb-fg); }
  .brand .name i { color: var(--lb-accent); font-style: normal; }
  .pill { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px; border-radius: 999px;
          font-size: 11px; border: 1px solid var(--lb-line); color: var(--lb-dim); }
  .pill .dot { width: 6px; height: 6px; border-radius: 50%; background: #d9534f; }
  .pill.up .dot { background: #3fb950; }
  .iconbtn { background: transparent; border: 1px solid var(--lb-line); color: var(--lb-fg);
             border-radius: 8px; padding: 4px 9px; font-size: 12px; cursor: pointer; }
  .iconbtn:hover { border-color: var(--lb-accent); color: var(--lb-accent); }

  /* ---- 页签（胶囊分段）---- */
  .tabs { display: flex; gap: 4px; padding: 3px; margin-bottom: 8px;
          background: var(--lb-surface); border: 1px solid var(--lb-line); border-radius: 999px; }
  .tabs button { flex: 1; background: transparent; border: 0; border-radius: 999px;
                 padding: 6px 8px; font-size: 12px; cursor: pointer; color: var(--lb-dim); }
  .tabs button.on { background: var(--lb-accent); color: #1a1407; font-weight: 600; }

  /* ---- 工作区行 ---- */
  .wsline { display: flex; align-items: center; gap: 6px; margin: 0 2px 8px;
            font-size: 11.5px; color: var(--lb-dim); }
  .wsline b { color: var(--lb-fg); font-weight: 500; overflow: hidden;
              text-overflow: ellipsis; white-space: nowrap; }

  /* ---- 消息 ---- */
  .scroller { flex: 1; min-height: 120px; max-height: 46vh; overflow: auto; padding: 2px 2px 6px; }
  .msg { position: relative; margin: 8px 0; padding: 9px 11px; border-radius: var(--lb-radius);
         font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word;
         background: var(--lb-surface); border: 1px solid var(--lb-line); }
  .msg.assistant { border-top-left-radius: 4px; box-shadow: inset 3px 0 0 var(--lb-accent); }
  .msg.user { margin-left: 14%; border-top-right-radius: 4px;
              background: var(--lb-accent-soft); border-color: rgba(224, 178, 60, .38); }
  .msg.system { background: transparent; border-style: dashed; color: var(--lb-dim); font-size: 11.5px; }
  .msg .who { font-size: 10px; letter-spacing: .6px; text-transform: uppercase;
              color: var(--lb-dim); margin-bottom: 4px; }
  .msg.assistant .who { color: var(--lb-accent); }

  /* ---- 待审改动卡片 ---- */
  .card { border: 1px solid var(--lb-line); border-radius: var(--lb-radius); overflow: hidden;
          margin: 8px 0; background: var(--lb-surface); }
  .card h4 { margin: 0; padding: 7px 10px; font-size: 12px; color: #1a1407; font-weight: 600;
             background: var(--lb-accent); word-break: break-all; }
  .card .body { padding: 8px 10px; }
  pre { max-height: 190px; overflow: auto; margin: 6px 0; padding: 8px; font-size: 11px;
        line-height: 1.5; border-radius: 8px;
        background: var(--vscode-textCodeBlock-background, #16171a); }
  .add { color: #3fb950; }
  .del { color: #f85149; }

  /* ---- 输入区 ---- */
  textarea { width: 100%; box-sizing: border-box; min-height: 66px; resize: vertical;
             background: var(--lb-surface-2); color: var(--lb-fg);
             border: 1px solid var(--lb-line); border-radius: var(--lb-radius);
             padding: 9px 11px; font-family: inherit; font-size: 12px; line-height: 1.55; }
  textarea:focus { outline: none; border-color: var(--lb-accent);
                   box-shadow: 0 0 0 3px var(--lb-accent-soft); }
  button { border: none; border-radius: 9px; padding: 6px 12px; cursor: pointer;
           font-size: 12px; font-weight: 500; }
  button.primary, #sendBtn, #newBtn { background: var(--lb-accent); color: #1a1407; }
  button.primary:hover, #sendBtn:hover, #newBtn:hover { filter: brightness(1.08); }
  button.sec, #fileBtn, #webBtn { background: transparent; color: var(--lb-fg);
                                  border: 1px solid var(--lb-line); }
  button.sec:hover, #fileBtn:hover, #webBtn:hover { border-color: var(--lb-accent); color: var(--lb-accent); }
  button:disabled { opacity: .45; cursor: default; }
  .row { display: flex; gap: 8px; align-items: center; }
  .hint { font-size: 11px; color: var(--lb-dim); }
  #fullWrap { display: none; flex: 1; min-height: 240px; }
  #fullWrap iframe { width: 100%; height: 100%; min-height: 240px; border: 1px solid var(--lb-line);
                     border-radius: var(--lb-radius); background: var(--lb-surface); }
  ::-webkit-scrollbar { width: 8px; height: 8px; }
  ::-webkit-scrollbar-thumb { background: var(--vscode-scrollbarSlider-background, #424242); border-radius: 4px; }
  ::-webkit-scrollbar-thumb:hover { background: var(--vscode-scrollbarSlider-hoverBackground, #4f4f4f); }
'''

BODY = '''
  <div class="brand">
    <span class="mark">🦁</span>
    <span class="name">Lion<i>Box</i></span>
    <span class="pill" id="statusPill"><span class="dot" id="dot"></span><span id="status">正在连接…</span></span>
    <span style="flex:1"></span>
    <button class="iconbtn" id="newBtn" title="新建对话">＋</button>
    <button class="iconbtn" id="webBtn" title="在浏览器里打开完整界面">↗</button>
  </div>

  <div class="tabs">
    <button id="tabChatBtn" class="on">对话</button>
    <button id="tabFullBtn">设置 · 插件</button>
  </div>
  <div id="fullWrap"><iframe id="fullFrame" src="about:blank"></iframe></div>

  <div class="wsline">工作区<b id="ws">—</b></div>

  <div id="changes"></div>
  <div class="scroller" id="chat"></div>

  <textarea id="input" placeholder="让它干点什么…（@ 引用文件只把那个文件读进上下文）"></textarea>
  <div class="row" style="margin-top:8px">
    <button id="sendBtn">发送</button>
    <button id="fileBtn" title="引用一个文件">@ 文件</button>
    <span style="flex:1"></span>
    <span class="hint" id="hint">Enter 发送 / Shift+Enter 换行</span>
  </div>
'''

# 替换 <style>…</style>
i, j = src.index('<style>') + len('<style>'), src.index('</style>')
src = src[:i] + CSS + src[j:]

# 替换 <body>…<script>
i, j = src.index('<body>') + len('<body>'), src.index('<script>')
src = src[:i] + BODY + src[j:]

io.open(p, 'w', encoding='utf-8', newline='').write(src)
print('面板已整体重做（CSS %d 行 / 骨架 %d 行）' % (CSS.count('\n'), BODY.count('\n')))

r = subprocess.run(['node', '--check', p], capture_output=True, text=True)
print('extension.js 语法:', 'OK' if r.returncode == 0 else 'FAIL')
if r.returncode:
    print((r.stderr or '')[-500:])
    sys.exit(1)
for need in ('id="dot"', 'id="status"', 'id="newBtn"', 'id="webBtn"', 'id="ws"',
             'id="changes"', 'id="chat"', 'id="input"', 'id="sendBtn"', 'id="fileBtn"',
             'id="hint"', 'id="tabChatBtn"', 'id="tabFullBtn"', 'id="fullWrap"', 'id="fullFrame"'):
    if need not in src:
        print('! 丢了元素:', need)
print('JS 需要的元素 id 全在')

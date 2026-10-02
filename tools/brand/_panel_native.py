#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""右栏面板按 **VS Code / code-server 自带的视图规范**重做，并修掉"下半部分断掉"的问题。

用户反馈两条：
  1. "整个重新设置" —— 上一版我自己发明了一套（金色渐变、圆角卡片、胶囊页签），
     看起来不像 code-server 里的东西，而且**下半部分被截断**；
  2. "去网上搜搜 code-server 界面长什么样，学学他的风格"。

查证结论（[code-server 的界面就是 VS Code workbench](https://deepwiki.com/microsoft/vscode/3-application-lifecycle-and-bootstrap)，
  视图由[活动栏/面板/视图容器](https://deepwiki.com/microsoft/vscode/3.3-language-features)那套规范渲染，
  颜色全部来自主题变量）。所以"学它"= 用 VS Code 自己的设计记号，而不是自创：
   · 视图标题栏：高 22px、11px 大写字母间距的标题、右侧工具图标（hover 才有底色）；
   · 页签：**下划线**表示选中（不是胶囊/实底按钮）；
   · 列表：扁平行 + hover 底色，圆角只用 2-3px；
   · 输入/按钮：2px 圆角，focus 用 `--vscode-focusBorder` 描边。

【下半部分为什么会断】上一版 body 用了 flex 列布局，但 `.scroller` 写了 `max-height: 46vh`
又不给 `min-height: 0`，子项把容器撑高、输入区被挤出视口裁掉。这版：
   body 固定 100% 高 + `overflow:hidden`，`.scroller` 用 `flex:1 1 auto; min-height:0`，
   输入区包在 `.composer { flex:none }` 里 —— 输入框永远贴底可见。
"""

import io, os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
p = os.path.join(ROOT, 'extensions', 'vscode', 'src', 'extension.js')
src = io.open(p, encoding='utf-8').read()

CSS = r'''
  /* ===== LionBox Agent 面板：按 VS Code 视图规范排版（22px 标题栏 / 下划线页签 /
     扁平列表 / 2px 圆角），颜色全取主题变量 —— 所以和 code-server 里的其它视图是一套观感 ===== */
  html, body { height: 100%; }
  body { margin: 0; padding: 0; display: flex; flex-direction: column; overflow: hidden;
         font-family: var(--vscode-font-family); font-size: 12px; color: var(--vscode-foreground); }

  .vhead { flex: none; display: flex; align-items: center; height: 22px; padding: 0 4px 0 10px; }
  .vhead .title { font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase;
                  color: var(--vscode-sideBarSectionHeader-foreground, var(--vscode-foreground)); }
  .vhead .actions { margin-left: auto; display: flex; gap: 2px; }
  .vhead button { background: transparent; border: 0; color: inherit; padding: 2px 5px;
                  border-radius: 3px; cursor: pointer; font-size: 12px; }
  .vhead button:hover { background: var(--vscode-toolbar-hoverBackground, rgba(90,93,94,.31)); }

  .statusline { flex: none; display: flex; align-items: center; gap: 6px; padding: 0 10px 4px;
                font-size: 11px; color: var(--vscode-descriptionForeground); }
  .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--vscode-testing-iconFailed, #d33); }
  .dot.up { background: var(--vscode-testing-iconPassed, #2ea043); }
  .wsline { flex: none; padding: 0 10px 6px; font-size: 11px; color: var(--vscode-descriptionForeground);
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .wsline b { color: var(--vscode-foreground); font-weight: 500; }

  .tabs { flex: none; display: flex; border-bottom: 1px solid var(--vscode-panel-border, #3c3c3c); }
  .tabs button { flex: 1; background: transparent; border: 0; border-bottom: 1px solid transparent;
                 padding: 5px 8px; font-size: 11.5px; cursor: pointer;
                 color: var(--vscode-descriptionForeground); }
  .tabs button:hover { color: var(--vscode-foreground); }
  .tabs button.on { color: var(--vscode-foreground); border-bottom-color: var(--vscode-focusBorder, #007fd4); }

  .scroller { flex: 1 1 auto; min-height: 0; overflow: auto; }
  .msg { padding: 6px 10px; border-bottom: 1px solid var(--vscode-panel-border, rgba(128,128,128,.18));
         white-space: pre-wrap; word-break: break-word; line-height: 1.55; }
  .msg:hover { background: var(--vscode-list-hoverBackground, rgba(90,93,94,.15)); }
  .msg.system { color: var(--vscode-descriptionForeground); font-size: 11.5px; }
  .msg .who { font-size: 10px; letter-spacing: .06em; text-transform: uppercase;
              color: var(--vscode-descriptionForeground); margin-bottom: 2px; }
  .msg.user .who { color: var(--vscode-textLink-foreground, #3794ff); }
  .msg.assistant .who { color: var(--vscode-charts-green, #2ea043); }

  .card { border: 1px solid var(--vscode-panel-border, #3c3c3c); border-radius: 3px; margin: 6px 8px; }
  .card h4 { margin: 0; padding: 4px 8px; font-size: 11.5px; font-weight: 600; word-break: break-all;
             background: var(--vscode-sideBarSectionHeader-background, rgba(255,255,255,.04)); }
  .card .body { padding: 6px 8px; }
  pre { max-height: 180px; overflow: auto; margin: 4px 0; padding: 6px; font-size: 11px;
        border-radius: 3px; background: var(--vscode-textCodeBlock-background, #1e1e1e); }
  .add { color: var(--vscode-gitDecoration-addedResourceForeground, #2ea043); }
  .del { color: var(--vscode-gitDecoration-deletedResourceForeground, #d33); }

  .composer { flex: none; padding: 6px 8px 8px; border-top: 1px solid var(--vscode-panel-border, #3c3c3c); }
  textarea { width: 100%; box-sizing: border-box; min-height: 56px; max-height: 28vh; resize: vertical;
             padding: 6px 8px; font-family: inherit; font-size: 12px; line-height: 1.5; border-radius: 2px;
             background: var(--vscode-input-background); color: var(--vscode-input-foreground);
             border: 1px solid var(--vscode-input-border, transparent); }
  textarea:focus { outline: 1px solid var(--vscode-focusBorder, #007fd4); outline-offset: -1px; }
  textarea::placeholder { color: var(--vscode-input-placeholderForeground, #888); }
  .row { display: flex; gap: 6px; align-items: center; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground);
           border: 0; border-radius: 2px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
  button:hover { background: var(--vscode-button-hoverBackground, #1177bb); }
  button.sec, #fileBtn { background: var(--vscode-button-secondaryBackground, #3a3d41);
                         color: var(--vscode-button-secondaryForeground, #ddd); }
  button.sec:hover, #fileBtn:hover { background: var(--vscode-button-secondaryHoverBackground, #45494e); }
  button:disabled { opacity: .45; cursor: default; }
  .hint { font-size: 11px; color: var(--vscode-descriptionForeground); }

  #fullWrap { flex: 1 1 auto; min-height: 0; display: none; }
  #fullWrap iframe { width: 100%; height: 100%; border: 0; background: var(--vscode-editor-background, #1e1e1e); }
'''

BODY = '''
  <div class="vhead">
    <span class="title">LionBox Agent</span>
    <span class="actions">
      <button id="newBtn" title="新建对话">＋</button>
      <button id="webBtn" title="在浏览器里打开完整界面">↗</button>
    </span>
  </div>
  <div class="statusline" id="statusline"><span class="dot" id="dot"></span><span id="status">正在连接…</span></div>
  <div class="wsline" id="wsline">工作区 <b id="ws">—</b></div>

  <div class="tabs">
    <button id="tabChatBtn" class="on">对话</button>
    <button id="tabFullBtn">设置 · 插件</button>
  </div>
  <div id="fullWrap"><iframe id="fullFrame" src="about:blank"></iframe></div>

  <div id="changes"></div>
  <div class="scroller" id="chat"></div>

  <div class="composer">
    <textarea id="input" placeholder="让它干点什么…（@ 引用文件只把那个文件读进上下文）"></textarea>
    <div class="row" style="margin-top:6px">
      <button id="sendBtn">发送</button>
      <button class="sec" id="fileBtn" title="引用一个文件">@ 文件</button>
      <span style="flex:1"></span>
      <span class="hint" id="hint">Enter 发送 / Shift+Enter 换行</span>
    </div>
  </div>
'''

i, j = src.index('<style>') + len('<style>'), src.index('</style>')
src = src[:i] + CSS + src[j:]
i, j = src.index('<body>') + len('<body>'), src.index('<script>')
src = src[:i] + BODY + src[j:]

# 切页签时隐藏：状态行 + 工作区行 + 输入区（id 跟着新骨架走）
src = src.replace("['statusPill', 'wsline', 'input']", "['statusline', 'wsline', 'composer']", 1)
src = src.replace("['status', 'ws', 'input']", "['statusline', 'wsline', 'composer']", 1)
# .composer 是包裹层，给它一个 id
src = src.replace('<div class="composer">', '<div class="composer" id="composer">', 1)

io.open(p, 'w', encoding='utf-8', newline='').write(src)
print('CSS %d 行 / 骨架 %d 行' % (CSS.count('\n'), BODY.count('\n')))

r = subprocess.run(['node', '--check', p], capture_output=True, text=True)
print('extension.js:', 'OK' if r.returncode == 0 else 'FAIL')
if r.returncode:
    print((r.stderr or '')[-400:]); sys.exit(1)
for need in ('id="dot"', 'id="status"', 'id="newBtn"', 'id="webBtn"', 'id="ws"', 'id="changes"',
             'id="chat"', 'id="input"', 'id="sendBtn"', 'id="fileBtn"', 'id="hint"',
             'id="tabChatBtn"', 'id="tabFullBtn"', 'id="fullWrap"', 'id="fullFrame"'):
    assert need in src, '丢了 ' + need
print('JS 需要的 id 全在；输入区包在 .composer(flex:none) 里，不会再被挤出视口')

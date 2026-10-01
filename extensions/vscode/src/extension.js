'use strict';

/**
 * LionBox for VS Code —— 右侧栏的 Agent 面板
 *
 * 【这一版改了什么】原来是"开个面板用 iframe 内嵌整个 Web UI"。现在照用户的要求做成
 * Cursor 那种形态：**右侧栏（secondary sidebar）里直接就是一个 Agent 面板**，不用跳出去，
 * 而且三件事跟 VS Code 绑在一起：
 *
 *   1. 工作区 = 当前打开的文件夹。插件把 workspaceFolders[0] 交给后端建/复用工作区，
 *      Agent 就在这个项目里干活，不用用户再手填路径。
 *   2. @ 引用单文件。面板输入框里 @ 一下，挑一个文件插成 @file:相对路径 ——
 *      后端只把这个文件读进上下文，比"整个项目塞进去"省得多（上下文窗口默认才 16K）。
 *   3. 改动人工审核。AI 改的文件先攒成待审改动，面板里列出 diff，
 *      点「通过」才真正写进磁盘，点「打回」写理由让它重写 —— 相当于作业签字。
 *      （默认行为由后端插件 plugin.change-review 决定；这里也能一键切换。）
 *
 * 官方文档：
 *   Webview API          https://code.visualstudio.com/api/extension-guides/webview
 *   Webview View          https://code.visualstudio.com/api/references/vscode-api#WebviewView
 *   Contribution Points   https://code.visualstudio.com/api/references/contribution-points
 */

const vscode = require('vscode');

const VIEW_TYPE = 'lionbox.webui';
const AGENT_VIEW_ID = 'lionbox.agent';

/** @type {vscode.WebviewPanel | undefined} */
let currentPanel;
/** @type {vscode.WebviewView | undefined} */
let agentView;
/** 当前会话 id（面板里新建对话会换一个） */
let sessionId = null;
/** 服务端推给面板的最新状态 */
let state = { up: false, workspace: '', messages: [], changes: [], busy: false, error: '' };

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------

function readConfig() {
  const cfg = vscode.workspace.getConfiguration('lionbox');
  const baseUrl = String(cfg.get('baseUrl', 'http://127.0.0.1:8080')).replace(/\/+$/, '');
  const healthPath = String(cfg.get('healthPath', '/api/runtime/mode'));
  const probeTimeoutMs = Number(cfg.get('probeTimeoutMs', 1500)) || 1500;
  const pollMs = Number(cfg.get('pollMs', 1500)) || 1500;
  return { baseUrl, healthPath, probeTimeoutMs, pollMs };
}

/**
 * 后端是否已就绪。
 * 判定规则：收到**任何** HTTP 响应（200/401/404…）都说明端口已监听 => 就绪；
 *          只有连接被拒 / 超时（fetch reject）才算未就绪。
 */
async function isBackendUp({ baseUrl, healthPath, probeTimeoutMs }) {
  let target;
  try {
    target = new URL(healthPath, baseUrl).toString();
  } catch {
    return false;
  }
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), probeTimeoutMs);
  try {
    await fetch(target, { signal: ac.signal });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/** 调后端 JSON 接口；失败返回 { ok:false, error } */
async function api(path, { method = 'GET', body } = {}) {
  const { baseUrl } = readConfig();
  try {
    const res = await fetch(baseUrl + path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = {};
    try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
    return { ok: res.ok, status: res.status, json };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  }
}

// ---------------------------------------------------------------------------
// 工作区：把"当前打开的文件夹"交给后端
// ---------------------------------------------------------------------------

function workspaceFolder() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || !folders.length) return '';
  return folders[0].uri.fsPath;
}

/**
 * 让后端知道工作区是哪个文件夹，并拿到它的 workspaceId。
 * 找不到就新建一个 —— 用户打开哪个文件夹，Agent 就在哪个文件夹里干活。
 */
async function ensureWorkspace() {
  const path = workspaceFolder();
  if (!path) return { path: '', id: null };
  const list = await api('/api/workspaces');
  const items = (list.json && (list.json.data || list.json.workspaces || list.json.items)) || [];
  const arr = Array.isArray(items) ? items : (items.workspaces || []);
  for (const w of arr) {
    if (w && (w.path === path || w.rootPath === path || w.name === path)) {
      return { path, id: w.id || w.workspaceId || null };
    }
  }
  const created = await api('/api/workspaces', { method: 'POST', body: { path } });
  const d = (created.json && (created.json.data || created.json)) || {};
  return { path, id: d.id || d.workspaceId || null };
}

/** 建一条新会话（绑到当前工作区） */
async function newSession() {
  const ws = await ensureWorkspace();
  const body = { mode: 'standard' };
  if (ws.id) body.workspaceId = ws.id;
  const r = await api('/api/sessions', { method: 'POST', body });
  const d = (r.json && (r.json.data || r.json)) || {};
  sessionId = d.sessionId || d.id || null;
  return sessionId;
}

// ---------------------------------------------------------------------------
// 状态轮询 → 推给面板
// ---------------------------------------------------------------------------

let pollTimer = null;

async function refresh() {
  const cfg = readConfig();
  const up = await isBackendUp(cfg);
  const ws = ws0();
  if (!up) {
    state = { up: false, workspace: ws, messages: [], changes: [], busy: state.busy, error: '' };
    post();
    return;
  }
  if (!sessionId) {
    await newSession();
  }
  const hist = sessionId ? await api('/api/sessions/' + encodeURIComponent(sessionId) + '/history') : { json: {} };
  const msgs = (hist.json && (hist.json.data || hist.json.messages || hist.json.history)) || [];
  const ch = await api('/api/changes?sessionId=' + encodeURIComponent(sessionId || ''));
  const changes = (ch.json && ch.json.changes) || [];
  state = {
    up: true,
    workspace: ws,
    sessionId,
    messages: Array.isArray(msgs) ? msgs.filter(m => m && m.role !== 'tool').slice(-40) : [],
    changes,
    reviewEnabled: !!(ch.json && ch.json.enabled),
    busy: state.busy,
    error: '',
  };
  post();
}

function ws0() {
  return workspaceFolder();
}

function post() {
  if (agentView && agentView.webview) {
    agentView.webview.postMessage({ type: 'state', state });
  }
}

function startPolling() {
  const cfg = readConfig();
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(() => { refresh().catch(() => {}); }, cfg.pollMs);
}

// ---------------------------------------------------------------------------
// 面板 HTML
// ---------------------------------------------------------------------------

function panelHtml(webview, extensionUri) {
  const csp = webview.cspSource;
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${csp} 'unsafe-inline'; script-src ${csp} 'unsafe-inline';">
<style>
  :root { color-scheme: light dark; }
  body { font-family: var(--vscode-font-family); font-size: 12.5px; margin: 0; padding: 8px;
         color: var(--vscode-foreground); background: transparent; }
  .row { display: flex; gap: 6px; align-items: center; }
  .head { font-size: 11.5px; color: var(--vscode-descriptionForeground); margin-bottom: 6px;
          word-break: break-all; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--vscode-testing-iconFailed, #d33); flex: none; }
  .dot.up { background: var(--vscode-testing-iconPassed, #2ea043); }
  textarea { width: 100%; box-sizing: border-box; min-height: 62px; resize: vertical;
             background: var(--vscode-input-background); color: var(--vscode-input-foreground);
             border: 1px solid var(--vscode-input-border, #555); border-radius: 4px; padding: 6px; font-family: inherit; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground);
           border: none; border-radius: 4px; padding: 4px 9px; cursor: pointer; font-size: 12px; }
  button.sec { background: var(--vscode-button-secondaryBackground, #3a3d41);
               color: var(--vscode-button-secondaryForeground, #ddd); }
  button:disabled { opacity: .5; cursor: default; }
  .msg { border-left: 2px solid var(--vscode-panel-border, #444); padding: 4px 0 4px 8px; margin: 6px 0; white-space: pre-wrap; word-break: break-word; }
  .msg.user { border-color: var(--vscode-textLink-foreground, #3794ff); }
  .msg.assistant { border-color: var(--vscode-testing-iconPassed, #2ea043); }
  .msg.system { border-color: var(--vscode-editorWarning-foreground, #cca700); font-size: 11.5px; color: var(--vscode-descriptionForeground); }
  .who { font-size: 10.5px; color: var(--vscode-descriptionForeground); }
  .card { border: 1px solid var(--vscode-panel-border, #444); border-radius: 6px; padding: 6px; margin: 8px 0; }
  .card h4 { margin: 0 0 4px; font-size: 12px; word-break: break-all; }
  pre { max-height: 190px; overflow: auto; background: var(--vscode-textCodeBlock-background, #1e1e1e);
        padding: 6px; border-radius: 4px; font-size: 11px; margin: 4px 0; }
  .add { color: var(--vscode-gitDecoration-addedResourceForeground, #2ea043); }
  .del { color: var(--vscode-gitDecoration-deletedResourceForeground, #d33); }
  .hint { font-size: 11px; color: var(--vscode-descriptionForeground); margin-top: 4px; }
  .scroller { max-height: 42vh; overflow: auto; }
</style>
</head>
<body>
  <div class="head">
    <div class="row"><span class="dot" id="dot"></span><b id="status">正在连接…</b>
      <span style="flex:1"></span>
      <button class="sec" id="newBtn" title="新建对话">＋</button>
      <button class="sec" id="webBtn" title="打开完整 Web UI">Web</button>
    </div>
    <div id="ws" style="margin-top:4px"></div>
  </div>

  <div id="changes"></div>

  <div class="scroller" id="chat"></div>

  <textarea id="input" placeholder="让它干点什么…（@ 引用文件：只把那个文件读进上下文）"></textarea>
  <div class="row" style="margin-top:6px">
    <button id="sendBtn">发送</button>
    <button class="sec" id="fileBtn" title="引用一个文件">@ 文件</button>
    <span style="flex:1"></span>
    <span class="hint" id="hint"></span>
  </div>

<script>
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  let lastState = {};

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  }

  function render(s) {
    lastState = s || {};
    $('dot').className = 'dot' + (s.up ? ' up' : '');
    $('status').textContent = s.up ? '已连接' : '后端没起来（先启动 LionBox）';
    $('ws').textContent = s.workspace ? ('工作区：' + s.workspace) : '（VS Code 没打开文件夹：Agent 就没有工作区）';

    // 待审改动：AI 改的文件，等你点通过才落盘
    const box = $('changes');
    const list = s.changes || [];
    if (!list.length) {
      box.innerHTML = '';
    } else {
      box.innerHTML = '<div class="hint">待你审核的改动（' + list.length + '）：通过才写进磁盘</div>' +
        list.map(c => '<div class="card">' +
          '<h4>' + esc(c.path) + '</h4>' +
          '<div class="hint">' + esc(c.toolName || '') + ' · ' + esc(c.status || '') + '</div>' +
          '<pre>' + (c.diff || '').split('\\n').map(l =>
              '<span class="' + (l.startsWith('  +') ? 'add' : (l.startsWith('  -') ? 'del' : '')) + '">' +
              esc(l) + '</span>').join('\\n') + '</pre>' +
          '<div class="row">' +
            '<button data-approve="' + esc(c.id) + '">通过并写入</button>' +
            '<button class="sec" data-reject="' + esc(c.id) + '">打回</button>' +
          '</div></div>').join('');
    }

    const chat = $('chat');
    const atBottom = chat.scrollTop + chat.clientHeight >= chat.scrollHeight - 30;
    chat.innerHTML = (s.messages || []).map(m =>
      '<div class="msg ' + esc(m.role) + '"><div class="who">' +
      (m.role === 'user' ? '你' : (m.role === 'assistant' ? 'LionBox' : esc(m.role))) +
      '</div>' + esc(m.content) + '</div>').join('');
    if (atBottom) chat.scrollTop = chat.scrollHeight;

    $('sendBtn').disabled = !!s.busy || !s.up;
    $('hint').textContent = s.busy ? '正在干活…' : '';
  }

  $('sendBtn').onclick = () => {
    const t = $('input').value.trim();
    if (!t) return;
    vscode.postMessage({ type: 'chat', text: t });
    $('input').value = '';
  };
  $('input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { $('sendBtn').click(); }
  });
  $('fileBtn').onclick = () => vscode.postMessage({ type: 'pickFile' });
  $('newBtn').onclick = () => vscode.postMessage({ type: 'newSession' });
  $('webBtn').onclick = () => vscode.postMessage({ type: 'openWeb' });
  document.addEventListener('click', (e) => {
    const a = e.target.getAttribute && e.target.getAttribute('data-approve');
    const r = e.target.getAttribute && e.target.getAttribute('data-reject');
    if (a) vscode.postMessage({ type: 'approve', id: a });
    if (r) vscode.postMessage({ type: 'reject', id: r });
  });

  window.addEventListener('message', (ev) => {
    const m = ev.data || {};
    if (m.type === 'state') render(m.state);
    if (m.type === 'insertRef') {
      const box = $('input');
      box.value = (box.value + ' @file:' + m.path).replace(/^\\s+/, '');
      box.focus();
    }
  });
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// Agent 面板（右侧栏）
// ---------------------------------------------------------------------------

function registerAgentView(context) {
  const provider = {
    resolveWebviewView(view) {
      agentView = view;
      view.webview.options = { enableScripts: true };
      view.webview.html = panelHtml(view.webview, context.extensionUri);
      view.webview.onDidReceiveMessage(async (msg) => {
        try {
          await handleMessage(msg);
        } catch (e) {
          vscode.window.showErrorMessage('LionBox: ' + (e && e.message ? e.message : e));
        }
      });
      startPolling();
      refresh().catch(() => {});
    },
  };
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(AGENT_VIEW_ID, provider, {
      webviewOptions: { retainContextWhenHidden: true },
    })
  );
}

async function handleMessage(msg) {
  const cfg = readConfig();
  switch (msg.type) {
    case 'ready':
    case 'refresh':
      await refresh();
      break;
    case 'newSession': {
      await newSession();
      await refresh();
      break;
    }
    case 'chat': {
      if (!sessionId) await newSession();
      state.busy = true;
      post();
      const r = await api('/api/chat', { method: 'POST', body: { sessionId, message: msg.text } });
      state.busy = false;
      if (!r.ok) {
        vscode.window.showErrorMessage('LionBox: 发送失败 ' + (r.error || r.status));
      }
      await refresh();
      break;
    }
    case 'approve': {
      const r = await api('/api/changes/' + encodeURIComponent(msg.id) + '/approve', { method: 'POST' });
      vscode.window.showInformationMessage(
        r.ok ? '已通过并写入' : ('通过失败：' + (r.error || r.status)));
      await refresh();
      break;
    }
    case 'reject': {
      const reason = await vscode.window.showInputBox({
        prompt: '打回理由（会回给 AI，让它照着重写）',
        placeHolder: '例如：这段逻辑不对，用另一种写法',
      });
      if (reason === undefined) return;
      const r = await api('/api/changes/' + encodeURIComponent(msg.id) + '/reject',
        { method: 'POST', body: { reason } });
      if (!r.ok) vscode.window.showErrorMessage('打回失败：' + (r.error || r.status));
      await refresh();
      break;
    }
    case 'pickFile': {
      const path = await pickFile();
      if (path && agentView) {
        agentView.webview.postMessage({ type: 'insertRef', path });
      }
      break;
    }
    default:
      break;
  }
}

/** 挑一个文件，转成相对工作区的路径（后端认 @file:相对路径） */
async function pickFile() {
  const root = workspaceFolder();
  if (!root) {
    vscode.window.showWarningMessage('先打开一个文件夹 —— Agent 的工作区就是它。');
    return '';
  }
  const uris = await vscode.workspace.findFiles('**/*', '**/{node_modules,.git,dist,target,out}/**', 3000);
  const items = uris.map((u) => {
    let rel = vscode.workspace.asRelativePath(u, false);
    return { label: rel, description: '', uri: u };
  }).sort((a, b) => a.label.localeCompare(b.label));
  const picked = await vscode.window.showQuickPick(items, {
    placeHolder: '引用哪个文件进上下文？（只会读这一个文件）',
    matchOnDescription: true,
  });
  if (!picked) return '';
  return picked.label.replace(/\\/g, '/');
}

// ---------------------------------------------------------------------------
// 原来的 Web UI 面板（保留：需要完整界面时用）
// ---------------------------------------------------------------------------

async function openWebUI(context) {
  const { baseUrl } = readConfig();
  if (!(await isBackendUp(readConfig()))) {
    const pick = await vscode.window.showWarningMessage(
      'LionBox 后端没起来（' + baseUrl + '）。先启动 LionBox 主程序，再打开 Web UI。',
      '仍然打开', '取消');
    if (pick !== '仍然打开') return;
  }
  if (currentPanel) {
    currentPanel.reveal(vscode.ViewColumn.Beside);
    currentPanel.webview.html = webPanelHtml(baseUrl);
    return;
  }
  currentPanel = vscode.window.createWebviewPanel(VIEW_TYPE, 'LionBox', vscode.ViewColumn.Beside,
    { enableScripts: true, retainContextWhenHidden: true });
  currentPanel.webview.html = webPanelHtml(baseUrl);
  currentPanel.onDidDispose(() => { currentPanel = undefined; }, null, context.subscriptions);
}

/**
 * 用 iframe 内嵌而不是把页面源码塞进 webview.html：
 * LionBox 的 Web UI 里全是 fetch('/api/...') 相对路径，宿主一旦变成 vscode-webview:// 就全 404；
 * 放进 iframe 后页面源仍是 http://127.0.0.1:8080，相对路径天然正确。
 */
function webPanelHtml(baseUrl) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<style>html,body{height:100%;margin:0;background:#0d0e10}iframe{border:0;width:100%;height:100%}</style>
</head><body><iframe src="${baseUrl}" allow="clipboard-read; clipboard-write"></iframe></body></html>`;
}

// ---------------------------------------------------------------------------
// 激活
// ---------------------------------------------------------------------------

function activate(context) {
  registerAgentView(context);

  context.subscriptions.push(
    vscode.commands.registerCommand('lionbox.openAgent', async () => {
      await vscode.commands.executeCommand('lionbox.agent.focus');
    }),
    vscode.commands.registerCommand('lionbox.newSession', async () => {
      await newSession();
      await refresh();
      vscode.window.showInformationMessage('LionBox: 新对话已开始');
    }),
    vscode.commands.registerCommand('lionbox.referenceFile', async () => {
      const path = await pickFile();
      if (path) {
        vscode.env.clipboard.writeText('@file:' + path);
        vscode.window.showInformationMessage('已复制 @file:' + path + '（粘到面板输入框里）');
      }
    }),
    vscode.commands.registerCommand('lionbox.openWebUI', () => openWebUI(context)),
    vscode.commands.registerCommand('lionbox.reload', async () => {
      sessionId = null;
      await refresh();
    }),
    // 换了打开的文件夹 => 工作区跟着换，会话重开
    vscode.workspace.onDidChangeWorkspaceFolders(async () => {
      sessionId = null;
      await refresh();
    }),
    { dispose: () => { if (pollTimer) clearInterval(pollTimer); } }
  );
}

function deactivate() {
  if (pollTimer) clearInterval(pollTimer);
}

module.exports = { activate, deactivate };

'use strict';

/**
 * LionBox for VS Code —— 最小可用扩展
 *
 * 做法：新建一个 WebviewPanel，在面板 HTML 里用 <iframe> 内嵌本地 Web UI。
 *      「不把页面源码塞进 webview.html」是有意的：LionBox 的 Web UI 里有大量
 *      `fetch('/api/...')` 相对路径，一旦宿主变成 vscode-webview:// 就会全部 404；
 *      放进 iframe 后，页面源仍是 http://127.0.0.1:8080，相对路径天然正确。
 *
 * 官方文档：
 *   Webview API            https://code.visualstudio.com/api/extension-guides/webview
 *   createWebviewPanel     https://code.visualstudio.com/api/references/vscode-api#window.createWebviewPanel
 *   Contribution Points    https://code.visualstudio.com/api/references/contribution-points
 */

const vscode = require('vscode');

const VIEW_TYPE = 'lionbox.webui';

/** @type {vscode.WebviewPanel | undefined} */
let currentPanel;

/** 刷新令牌：避免异步探测回来时覆盖更新的页面。 */
let refreshToken = 0;

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------

function readConfig() {
  const cfg = vscode.workspace.getConfiguration('lionbox');
  const baseUrl = String(cfg.get('baseUrl', 'http://127.0.0.1:8080')).replace(/\/+$/, '');
  const healthPath = String(cfg.get('healthPath', '/api/runtime/mode'));
  const probeTimeoutMs = Number(cfg.get('probeTimeoutMs', 1500)) || 1500;
  return { baseUrl, healthPath, probeTimeoutMs };
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
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), probeTimeoutMs);
  try {
    await fetch(target, { method: 'GET', signal: controller.signal, cache: 'no-store' });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// HTML 渲染
// ---------------------------------------------------------------------------

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function getNonce() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let nonce = '';
  for (let i = 0; i < 32; i += 1) nonce += chars.charAt(Math.floor(Math.random() * chars.length));
  return nonce;
}

/**
 * @param {vscode.Webview} webview 用来拿 cspSource（VS Code 注入的默认样式来源）
 * @param {'checking'|'up'|'down'} state
 */
function renderHtml(webview, config, state) {
  const nonce = getNonce();
  const baseUrl = config.baseUrl;
  // 同时放行 127.0.0.1 与 localhost（Chromium 都视其为 potentially-trustworthy origin，
  // 所以不会被混合内容策略拦；但 CSP 的 frame-src 必须显式列出来）。
  const altHost = baseUrl.includes('127.0.0.1')
    ? baseUrl.replace('127.0.0.1', 'localhost')
    : baseUrl.replace('localhost', '127.0.0.1');
  const csp = [
    "default-src 'none'",
    `frame-src ${baseUrl} ${altHost}`,
    `style-src ${webview.cspSource} 'nonce-${nonce}'`,
    `script-src 'nonce-${nonce}'`
  ].join('; ');

  const body =
    state === 'up'
      ? `<iframe class="frame" src="${escapeHtml(baseUrl)}/" title="LionBox"></iframe>`
      : `<div class="card">
           <h1>LionBox</h1>
           <p class="msg">${
             state === 'checking'
               ? '正在检测本地服务 ...'
               : `连不上 <code>${escapeHtml(baseUrl)}</code>。<br/>请先启动 LionBox 后端，再点下面的按钮重试。`
           }</p>
           <pre class="hint">java -jar target/lion-code-agent-harness-1.0.0-SNAPSHOT.jar</pre>
           <button id="retry" type="button" ${state === 'checking' ? 'disabled' : ''}>重新检测</button>
         </div>`;

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="${csp}" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>LionBox</title>
  <style nonce="${nonce}">
    html, body { height: 100%; margin: 0; padding: 0; background: var(--vscode-editor-background); }
    .frame { display: block; width: 100%; height: 100vh; border: 0; }
    .card {
      height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center;
      gap: 10px; color: var(--vscode-foreground);
      font-family: var(--vscode-font-family); text-align: center;
    }
    h1 { margin: 0; font-size: 26px; font-weight: 600; letter-spacing: 1px; }
    .msg { margin: 0; opacity: .85; }
    code { background: var(--vscode-textCodeBlock-background); padding: 1px 5px; border-radius: 4px; }
    .hint {
      margin: 4px 0 8px; padding: 8px 12px; border-radius: 6px; font-size: 12px;
      background: var(--vscode-textCodeBlock-background); opacity: .9;
    }
    button {
      padding: 6px 16px; border: 0; border-radius: 4px; cursor: pointer;
      color: var(--vscode-button-foreground); background: var(--vscode-button-background);
      font-family: inherit;
    }
    button:hover:not(:disabled) { background: var(--vscode-button-hoverBackground); }
    button:disabled { opacity: .5; cursor: default; }
  </style>
</head>
<body>
  ${body}
  <script nonce="${nonce}">
    (function () {
      // acquireVsCodeApi() 每个文档只能调用一次，必须在点击回调外先取好
      var api = acquireVsCodeApi();
      var btn = document.getElementById('retry');
      if (btn) {
        btn.addEventListener('click', function () {
          btn.disabled = true;
          btn.textContent = '检测中 ...';
          api.postMessage({ type: 'retry' });
        });
      }
    })();
  </script>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// 面板
// ---------------------------------------------------------------------------

async function refreshPanel() {
  const panel = currentPanel;
  if (!panel) return;
  const token = (refreshToken += 1);
  const config = readConfig();

  panel.title = 'LionBox';
  panel.webview.html = renderHtml(panel.webview, config, 'checking');

  const up = await isBackendUp(config);
  // 期间用户又点了刷新 / 面板已关闭，则丢弃本次结果
  if (token !== refreshToken || currentPanel !== panel) return;

  panel.webview.html = renderHtml(panel.webview, config, up ? 'up' : 'down');
  panel.title = up ? 'LionBox' : 'LionBox（后端未启动）';
  if (!up) {
    void vscode.window.showWarningMessage(
      `LionBox: 连不上 ${config.baseUrl}。请先启动后端，或在设置里修改 lionbox.baseUrl。`
    );
  }
}

async function handleMessage(message) {
  if (!message || typeof message.type !== 'string') return;
  if (message.type === 'retry') {
    await refreshPanel();
    return;
  }
  if (message.type === 'openExternal' && typeof message.url === 'string') {
    const uri = vscode.Uri.parse(message.url);
    if (uri.scheme === 'http' || uri.scheme === 'https') {
      await vscode.env.openExternal(uri);
    }
  }
}

function openWebUI(context) {
  const column = vscode.window.activeTextEditor
    ? vscode.window.activeTextEditor.viewColumn
    : vscode.ViewColumn.One;

  if (currentPanel) {
    currentPanel.reveal(column);
    void refreshPanel();
    return;
  }

  currentPanel = vscode.window.createWebviewPanel(
    VIEW_TYPE,
    'LionBox',
    column,
    {
      enableScripts: true,
      // 聊天界面切后台再回来时不想丢状态；注意官方提示它会占内存。
      retainContextWhenHidden: true
    }
  );

  currentPanel.onDidDispose(
    () => {
      currentPanel = undefined;
    },
    null,
    context.subscriptions
  );

  currentPanel.webview.onDidReceiveMessage(handleMessage, null, context.subscriptions);

  void refreshPanel();
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

/**
 * @param {vscode.ExtensionContext} context
 */
function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand('lionbox.openWebUI', () => openWebUI(context)),
    vscode.commands.registerCommand('lionbox.reload', async () => {
      if (!currentPanel) {
        vscode.window.showInformationMessage('LionBox 面板还没打开，请先执行 "LionBox: 打开 LionBox Web UI"。');
        return;
      }
      await refreshPanel();
    }),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration('lionbox') && currentPanel) void refreshPanel();
    })
  );
}

function deactivate() {
  currentPanel = undefined;
}

module.exports = { activate, deactivate };

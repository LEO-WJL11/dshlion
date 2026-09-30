'use strict';

/**
 * LionBox 桌面套壳 —— 主进程
 *
 * 设计目标：**不重写界面**，只做三件事：
 *   1) 等本地后端（默认 http://127.0.0.1:8080）就绪（健康轮询 /api/runtime/mode）；
 *   2) 就绪后用 BrowserWindow.loadURL 加载现有 Web UI；
 *   3) 可选：随包附带并拉起 Spring Boot fat jar。
 *
 * 安全基线（依据 Electron 官方 Security 文档）：
 *   contextIsolation: true / nodeIntegration: false / sandbox: true / webSecurity: true
 *   —— 页面来自 HTTP，属于"非本地文件"，任何 Node 能力都不注入渲染进程。
 *
 * 官方文档：
 *   https://www.electronjs.org/docs/latest/tutorial/security
 *   https://www.electronjs.org/docs/latest/api/browser-window
 */

const { app, BrowserWindow, ipcMain, session, shell } = require('electron');
const path = require('node:path');
const http = require('node:http');
const fs = require('node:fs');
const { spawn } = require('node:child_process');

// ---------------------------------------------------------------------------
// 配置（全部可用环境变量覆盖，便于打包后按需调整）
// ---------------------------------------------------------------------------

/** Web UI 地址。默认指向本地 8080。 */
const BASE_URL = (process.env.LIONBOX_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
/** 后端健康检查路径：任何 HTTP 响应都算"已就绪"。 */
const HEALTH_PATH = process.env.LIONBOX_HEALTH_PATH || '/api/runtime/mode';
/** 等待后端就绪的总超时与轮询间隔。 */
const HEALTH_TIMEOUT_MS = Number(process.env.LIONBOX_HEALTH_TIMEOUT_MS || 120_000);
const HEALTH_INTERVAL_MS = Number(process.env.LIONBOX_HEALTH_INTERVAL_MS || 800);
/** 单次探测超时（连不上时 socket 会立刻 ECONNREFUSED，这里兜底慢响应）。 */
const PROBE_TIMEOUT_MS = Number(process.env.LIONBOX_PROBE_TIMEOUT_MS || 1500);

/** 后端 jar：显式指定优先；否则找随包资源；再否则找开发目录 ./backend。 */
const BUNDLED_JAR_NAME = 'lion-code-agent-harness.jar';
const ENV_JAR = process.env.LIONBOX_JAR || '';
/** 设为 0 则不自动拉起后端。 */
const AUTO_START_BACKEND = process.env.LIONBOX_AUTO_START !== '0';

/**
 * 用哪个 java 起后端。
 *
 * 顺序：环境变量 LIONBOX_JAVA > **随包自带的精简 JRE** > **主程序安装目录里的 JRE** > PATH 里的 java。
 *
 * 【为什么要三级兜底】桌面版有两种打包法：
 *   · 胖包（自带 runtime-jre，178MB）：用户机器上没装 Java 也能跑，但超过 GitHub 100MB
 *     单文件上限，进不了仓库，只能走 Releases；
 *   · 瘦包（不带 JRE，约 78MB，能进仓库）：这时就得靠"主程序安装目录里那份 JRE" —— 只要用户
 *     装过 LionBox 主程序（%LOCALAPPDATA%\Programs\LionBox），桌面版就能直接用它的 Java，
 *     不用再装一遍运行时。两级都找不到才退到 PATH 里的 java。
 */
function resolveJavaBin() {
  if (process.env.LIONBOX_JAVA) return process.env.LIONBOX_JAVA;
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';
  const candidates = [
    path.join(process.resourcesPath || '', 'backend', 'runtime-jre', 'bin', exe),
    path.join(process.env.LOCALAPPDATA || '', 'Programs', 'LionBox', 'runtime-jre', 'bin', exe),
    path.join(process.env['ProgramFiles'] || '', 'LionBox', 'runtime-jre', 'bin', exe),
  ];
  for (const c of candidates) {
    try {
      if (c && fs.existsSync(c)) return c;
    } catch {
      /* 忽略：继续找下一个 */
    }
  }
  return 'java';
}

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

/** @type {BrowserWindow | null} */
let mainWindow = null;
/** @type {import('node:child_process').ChildProcess | null} */
let backendProcess = null;
/** 防止并发重复执行启动流程。 */
let startupRunning = false;

// ---------------------------------------------------------------------------
// 工具函数
// ---------------------------------------------------------------------------

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 只允许打开 http/https 外部链接。 */
function openExternalIfSafe(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
      void shell.openExternal(parsed.toString());
    }
  } catch {
    /* 非法 URL 直接忽略 */
  }
}

/** 目标 URL 是否属于 Web UI 自身源（其余一律视为外部跳转）。 */
function isSameOrigin(rawUrl) {
  try {
    return new URL(rawUrl).origin === new URL(BASE_URL).origin;
  } catch {
    return false;
  }
}

/** 解析后端 jar 路径；找不到返回 null。 */
function resolveBackendJar() {
  const candidates = [];
  if (ENV_JAR) {
    candidates.push(path.resolve(ENV_JAR));
  }
  // 打包后：resources/backend/lion-code-agent-harness.jar（见 electron-builder.yml 的 extraResources）
  candidates.push(path.join(process.resourcesPath || '', 'backend', BUNDLED_JAR_NAME));
  // 开发期：desktop/electron/backend/lion-code-agent-harness.jar
  candidates.push(path.join(__dirname, 'backend', BUNDLED_JAR_NAME));

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
    } catch {
      /* 忽略 */
    }
  }
  return null;
}

/** 单次健康探测：收到任何 HTTP 响应即视为已就绪。 */
function probeOnce() {
  return new Promise((resolve) => {
    let target;
    try {
      target = new URL(HEALTH_PATH, BASE_URL);
    } catch {
      resolve(false);
      return;
    }
    const req = http.get(target, { timeout: PROBE_TIMEOUT_MS }, (res) => {
      res.resume(); // 丢掉 body
      resolve(true);
    });
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
    req.on('error', () => resolve(false));
  });
}

/** 轮询直到就绪或超时。onProgress(attempt) 用于刷新等待页。 */
async function waitForBackend(onProgress) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  let attempt = 0;
  while (Date.now() < deadline) {
    attempt += 1;
    if (await probeOnce()) return true;
    if (typeof onProgress === 'function') onProgress(attempt);
    await delay(HEALTH_INTERVAL_MS);
  }
  return false;
}

/** 进度推送给渲染进程（等待页）。 */
function sendStatus(payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('lionbox:status', payload);
  }
}

// ---------------------------------------------------------------------------
// 可选：拉起本地后端 jar
// ---------------------------------------------------------------------------

function startBackendIfConfigured() {
  if (!AUTO_START_BACKEND) return null;
  const jar = resolveBackendJar();
  if (!jar) {
    console.log('[lionbox] 未找到后端 jar（可用 LIONBOX_JAR 指定），跳过自动启动。');
    return null;
  }
  console.log('[lionbox] 启动后端:', jar);
  const javaBin = resolveJavaBin();
  console.log('[lionbox] 用这个 java:', javaBin);
  // cwd 设成 jar 所在目录：应用是按"jar 同级目录 / 工作目录"找本地模型运行时的
  // （LocalModelRuntime.appDirs），cwd 不对就找不到随包的 llama-server.exe 和权重。
  const child = spawn(javaBin, ['-jar', jar], {
    cwd: path.dirname(jar),
    stdio: 'inherit',
    windowsHide: false
  });
  child.on('error', (err) => console.error('[lionbox] 后端启动失败:', err.message));
  child.on('exit', (code, signal) => {
    console.log(`[lionbox] 后端退出 code=${code} signal=${signal}`);
    backendProcess = null;
  });
  return child;
}

function stopBackendIfOwned() {
  if (backendProcess && !backendProcess.killed) {
    backendProcess.kill();
  }
  backendProcess = null;
}

// ---------------------------------------------------------------------------
// 启动流程：先展示等待页 → 轮询 → 加载 Web UI
// ---------------------------------------------------------------------------

async function runStartupSequence() {
  if (startupRunning) return;
  startupRunning = true;
  try {
    sendStatus({ phase: 'waiting', message: `正在等待本地服务 ${BASE_URL} ...`, attempt: 0 });
    const ready = await waitForBackend((attempt) => {
      sendStatus({
        phase: 'waiting',
        message: `正在等待本地服务 ${BASE_URL} ...（第 ${attempt} 次探测）`,
        attempt
      });
    });

    if (ready) {
      sendStatus({ phase: 'loading', message: '服务已就绪，正在加载界面 ...', attempt: 0 });
      if (!mainWindow || mainWindow.isDestroyed()) return;
      await mainWindow.loadURL(BASE_URL);
    } else {
      sendStatus({
        phase: 'failed',
        message:
          `等待超时（${Math.round(HEALTH_TIMEOUT_MS / 1000)} 秒）：${BASE_URL} 上没有响应。\n` +
          '请先启动 LionBox 后端（java -jar target/lion-code-agent-harness-*.jar），' +
          '或设置环境变量 LIONBOX_JAR 指向 jar 让本应用自动拉起。',
        attempt: 0
      });
    }
  } catch (err) {
    sendStatus({ phase: 'failed', message: `加载失败：${err && err.message ? err.message : err}`, attempt: 0 });
  } finally {
    startupRunning = false;
  }
}

// ---------------------------------------------------------------------------
// 窗口
// ---------------------------------------------------------------------------

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    show: false,
    backgroundColor: '#0f1115',
    title: 'LionBox',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      // 下面几项就是 Electron 官方 Security 检查表的核心要求
      contextIsolation: true,
      nodeIntegration: false,
      nodeIntegrationInWorker: false,
      nodeIntegrationInSubFrames: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      experimentalFeatures: false,
      spellcheck: false
    }
  });

  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  // 新窗口一律拒绝，交给系统浏览器
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    openExternalIfSafe(url);
    return { action: 'deny' };
  });

  // 只允许在 Web UI 同源内导航；外部链接交给系统浏览器
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (isSameOrigin(url)) return;
    event.preventDefault();
    openExternalIfSafe(url);
  });

  // 页面崩溃/加载失败时给出可读提示（等待页自身不会失败）
  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
    if (validatedURL && validatedURL.startsWith('file://')) return;
    console.error(`[lionbox] did-fail-load ${errorCode} ${errorDescription} ${validatedURL}`);
  });

  void mainWindow.loadFile(path.join(__dirname, 'status.html'));
  return mainWindow;
}

// ---------------------------------------------------------------------------
// IPC（只服务等待页，且校验来源）
// ---------------------------------------------------------------------------

/** 只信任我们自己加载的本地等待页发来的 IPC。 */
function isTrustedSender(event) {
  if (!mainWindow || mainWindow.isDestroyed()) return false;
  if (event.sender !== mainWindow.webContents) return false;
  const url = event.senderFrame ? event.senderFrame.url : '';
  return typeof url === 'string' && url.startsWith('file://');
}

function registerIpc() {
  ipcMain.handle('lionbox:retry', (event) => {
    if (!isTrustedSender(event)) return { ok: false, reason: 'untrusted-sender' };
    void runStartupSequence();
    return { ok: true };
  });

  ipcMain.handle('lionbox:open-external', (event, url) => {
    if (!isTrustedSender(event)) return { ok: false, reason: 'untrusted-sender' };
    openExternalIfSafe(String(url));
    return { ok: true };
  });
}

// ---------------------------------------------------------------------------
// 权限：默认全部拒绝，只对本机 UI 源放行剪贴板写入
// ---------------------------------------------------------------------------

function hardenSession() {
  const allowedPermissions = new Set(['clipboard-sanitized-write', 'clipboard-read']);
  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    const url = webContents ? webContents.getURL() : '';
    callback(allowedPermissions.has(permission) && isSameOrigin(url));
  });
  session.defaultSession.setPermissionCheckHandler((_webContents, permission, requestingOrigin) => {
    return allowedPermissions.has(permission) && isSameOrigin(requestingOrigin);
  });
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    hardenSession();
    registerIpc();
    createWindow();
    backendProcess = startBackendIfConfigured();
    void runStartupSequence();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
        void runStartupSequence();
      }
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('before-quit', stopBackendIfOwned);
  app.on('will-quit', stopBackendIfOwned);
}

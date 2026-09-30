'use strict';

/**
 * LionBox 桌面套壳 —— preload
 *
 * 因为 Web UI 来自 HTTP（非本地文件），主进程与页面之间只通过这一个白名单桥通信。
 * 渲染进程永远拿不到 require / fs / child_process。
 *
 * 官方文档：
 *   https://www.electronjs.org/docs/latest/tutorial/security#20-do-not-expose-electron-apis-to-untrusted-web-content
 *   https://www.electronjs.org/docs/latest/api/context-bridge
 */

const { contextBridge, ipcRenderer } = require('electron');

/** sandbox: true 时 preload 里只有一个精简版 process，取值要兜底。 */
const versions = (() => {
  try {
    return {
      electron: process.versions.electron || '',
      chrome: process.versions.chrome || '',
      node: process.versions.node || ''
    };
  } catch {
    return { electron: '', chrome: '', node: '' };
  }
})();

contextBridge.exposeInMainWorld('lionbox', {
  /** 订阅后端就绪进度；返回取消订阅函数。 */
  onStatus(callback) {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, payload) => {
      try {
        callback(payload);
      } catch (err) {
        console.error('[lionbox] status callback failed', err);
      }
    };
    ipcRenderer.on('lionbox:status', listener);
    return () => ipcRenderer.removeListener('lionbox:status', listener);
  },

  /** 强制重新走一遍"等待后端 → 加载界面"流程。 */
  retry() {
    return ipcRenderer.invoke('lionbox:retry');
  },

  /** 用系统默认浏览器打开链接（只允许 http/https）。 */
  openExternal(url) {
    return ipcRenderer.invoke('lionbox:open-external', String(url));
  },

  versions
});

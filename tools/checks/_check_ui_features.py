# -*- coding: utf-8 -*-
r"""界面行为测试：真的把新写的渲染函数跑起来看输出（假 DOM + node）。

测两件事：
  1. 对话列表按工作区分组成可收起的选项卡；每个对话能选工作区
  2. 设置页的"本地模型 / llama.cpp"页签：模型版本 + 全部启动参数
比 grep 关键字强的地方：函数真跑一遍，能抓到"写了但一跑就报错"的问题。
"""
import io
import os
import re
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UI = os.path.join(ROOT, 'web', 'index.html')

html = io.open(UI, encoding='utf-8', newline='').read()
m = re.search(r'(?s)<script>(.*?)</script>', html)
assert m, 'index.html 里没找到 <script>'
app_js = m.group(1)

PREAMBLE = r'''
// ---------------- 最小假 DOM / 假环境 ----------------
const __store = {};
globalThis.localStorage = {
  getItem: k => (k in __store ? __store[k] : null),
  setItem: (k, v) => { __store[k] = String(v); },
  removeItem: k => { delete __store[k]; }
};
const __els = {};
function __mkEl(id) {
  return {
    id: id, innerHTML: '', textContent: '', value: '', checked: false,
    style: {}, dataset: {},
    classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
    appendChild(){}, removeChild(){}, setAttribute(){}, removeAttribute(){},
    addEventListener(){}, removeEventListener(){}, focus(){}, blur(){}, click(){},
    querySelector(){ return null; }, querySelectorAll(){ return []; },
    getBoundingClientRect(){ return {top:0,left:0,width:0,height:0}; },
    scrollIntoView(){}, insertAdjacentHTML(){}, remove(){}
  };
}
globalThis.document = {
  getElementById(id) { return __els[id] || (__els[id] = __mkEl(id)); },
  querySelector() { return null; },
  querySelectorAll() { return []; },
  createElement(tag) { return __mkEl('new-' + tag); },
  addEventListener(){}, removeEventListener(){},
  body: __mkEl('body'), documentElement: __mkEl('html'),
  execCommand(){ return true; }
};
globalThis.window = globalThis;
globalThis.window.addEventListener = function(){};
globalThis.window.removeEventListener = function(){};
globalThis.location = { href: 'http://127.0.0.1:8899/', reload(){} };
globalThis.navigator = { clipboard: { writeText: () => Promise.resolve() }, userAgent: 'node' };
globalThis.alert = function(msg) { (globalThis.__alerts = globalThis.__alerts || []).push(String(msg)); };
globalThis.confirm = function() { return true; };
globalThis.prompt = function() { return 'x'; };
globalThis.setTimeout = function() { return 0; };
globalThis.clearTimeout = function(){};
globalThis.setInterval = function() { return 0; };
globalThis.clearInterval = function(){};
globalThis.EventSource = function() { this.close = function(){}; this.addEventListener = function(){}; };
globalThis.marked = { parse: s => s };
globalThis.hljs = { highlightElement(){}, highlight(){ return { value: '' }; } };
globalThis.AbortController = function() { this.signal = {}; this.abort = function(){}; };

globalThis.__localStatus = { phase: 'idle', modelInstalled: true, running: true,
  modelFile: 'lion-merged-Q4_K_M.gguf', downloadingFile: '', downloadBytes: 0, downloadTotal: -1 };
globalThis.__fetchLog = [];
globalThis.__fetchBodies = [];
globalThis.__configData = {
  modelDir: 'D:\\models-here',
  modelFile: 'lion-merged-Q4_K_M.gguf', modelName: 'lion-models1', host: '127.0.0.1', port: 8788,
  ctxSize: 262144, ngl: 999, kvCacheTypeK: 'q4_0', kvCacheTypeV: 'q4_0', flashAttn: true,
  parallelSlots: 1, threads: 0, batchSize: 2048, ubatchSize: 512, temperature: 0.2, topP: 0.9,
  topK: 40, minP: 0.05, repeatPenalty: 1.05, repeatLastN: 256, seed: -1, maxPredict: 4096,
  extraArgs: '', running: true
};
globalThis.__modelsData = [
  { file: 'lion-merged-Q8_0.gguf', label: 'Q8_0', sizeGb: 8.87, note: '原版', current: false, downloaded: true },
  { file: 'lion-merged-Q4_K_M.gguf', label: 'Q4_K_M', sizeGb: 5.24, note: '平衡', current: true, downloaded: true },
  { file: 'lion-merged-IQ4_XS.gguf', label: 'IQ4_XS', sizeGb: 4.87, note: '最快', current: false, downloaded: false },
  // 用户自己丢进模型目录的 GGUF
  { file: 'my-own-7b-Q4_K_M.gguf', label: 'my-own-7b-Q4_K_M.gguf', sizeGb: 4.1,
    note: '你自己放进模型目录的', custom: true,
    current: false, configured: false, downloaded: true, downloading: false }
];
globalThis.fetch = function(url, opt) {
  var u = String(url);
  globalThis.__fetchLog.push(u);
  if (opt && opt.body) { globalThis.__fetchBodies.push({ url: u, body: String(opt.body) }); }
  var data = { success: true, data: {} };
  if (u.indexOf('/api/runtime/local/config') >= 0) { data.data = globalThis.__configData; }
  else if (u.indexOf('/api/runtime/local/models') >= 0) { data.data = globalThis.__modelsData; }
  // 注意顺序：/local/config 和 /local/models 必须先匹配掉，这条放最后
  else if (u.indexOf('/api/runtime/local') >= 0) { data.data = globalThis.__localStatus; }
  else if (u.indexOf('/history') >= 0) { data.data = globalThis.__historyData || []; }
  else if (u.indexOf('/api/sessions') >= 0) { data.data = []; }
  else if (u.indexOf('/api/workspaces') >= 0) { data.data = []; }
  return Promise.resolve({ json: function() { return Promise.resolve(data); }, ok: true, status: 200 });
};

var __fails = [];
function ok(cond, label, extra) {
  if (cond) { console.log('  [OK]   ' + label); }
  else { console.log('  [FAIL] ' + label + (extra ? '  ' + extra : '')); __fails.push(label); }
}
function tick() { return new Promise(function(r) { r(); }); }
'''

TESTS = r'''
(async function() {
 try {
  // ==================== 1. 工作区选项卡 ====================
  console.log('--- 对话列表：按工作区分组的选项卡 ---');
  App.sessionId = 'sess-B';
  var sessions = [
    { sessionId: 'sess-A', workspaceId: 'C:\\proj\\alpha', name: '甲对话', mode: 'STANDARD' },
    { sessionId: 'sess-B', workspaceId: 'C:\\proj\\beta',  name: '乙对话', mode: 'STANDARD' }
  ];
  var workspaces = [
    { path: 'C:\\proj\\alpha' }, { path: 'C:\\proj\\beta' }, { path: 'C:\\proj\\gamma' }
  ];
  App.sessionsData = sessions;
  App.workspacesData = workspaces;
  App.renderSessionTabs(sessions, workspaces);
  var html = document.getElementById('sessionList').innerHTML;
  ok(html.indexOf('ws-tab') >= 0, '渲染出了工作区选项卡');
  ok(html.indexOf('alpha') >= 0 && html.indexOf('beta') >= 0, '两个有对话的工作区都在');
  ok((html.match(/ws-tab-head/g) || []).length === 2,
     '★ 列表里只剩 2 个有对话的工作区选项卡（没对话的不占位置）');
  ok(html.indexOf('ws-name">gamma') < 0, '★ 没有对话的工作区不出现在列表里');
  ok(html.indexOf('甲对话') >= 0 && html.indexOf('乙对话') >= 0, '对话标题在里面');
  ok(html.indexOf('ws-pick') >= 0, '每个对话都有"选工作区"的下拉');
  ok(html.indexOf('App.toggleWsTab') >= 0, '表头能点开/收起');
  ok(html.indexOf('App.newSessionIn') >= 0, '表头上有"在这个工作区新建对话"');
  ok(html.indexOf('ws-count') >= 0, '显示每个工作区里的对话数');
  ok(html.indexOf('beta') < html.indexOf('alpha'), '当前对话所在工作区排最前');

  localStorage.setItem('lionbox.wsCollapsed', JSON.stringify(['C:\\proj\\beta']));
  App.renderSessionTabs(sessions, workspaces);
  var html2 = document.getElementById('sessionList').innerHTML;
  ok(html2.indexOf('乙对话') < 0, '收起的工作区里不再显示对话');
  ok(html2.indexOf('▶') >= 0, '收起的工作区显示"▶"');

  App.toggleWsTab('C:\\proj\\beta');
  var html3 = document.getElementById('sessionList').innerHTML;
  ok(html3.indexOf('乙对话') >= 0, 'App.toggleWsTab 展开后又能看到对话');
  ok(JSON.parse(localStorage.getItem('lionbox.wsCollapsed')).indexOf('C:\\proj\\beta') < 0,
     '展开状态记进了 localStorage');

  // 一个对话都没有：即使注册表里有工作区，也一个都不显示
  App.renderSessionTabs([], workspaces);
  var html4 = document.getElementById('sessionList').innerHTML;
  ok(html4.indexOf('ws-tab') < 0, '★ 没有对话时不列任何工作区（含以前用过、现在空的）');
  ok(html4.indexOf('还没有对话') >= 0, '给出“还没有对话”的引导');
  ok(html4.indexOf('ws-empty') < 0, '不会再出现“这个工作区还没有对话”的空壳');

  // 但「选工作区」下拉里仍然列全部工作区（能把对话挪进一个目前没对话的工作区）
  App.renderSessionTabs(sessions, workspaces);
  var html5 = document.getElementById('sessionList').innerHTML;
  ok(html5.indexOf('value="C:\\proj\\gamma"') >= 0,
     '★ 下拉里仍然能选到那个没对话的工作区 gamma');

  // ==================== 2. 给对话换工作区 ====================
  console.log('--- 给某个对话换工作区 ---');
  globalThis.__fetchLog = [];
  App.moveSession('sess-A', 'C:\\proj\\gamma');
  await tick();
  ok(globalThis.__fetchLog.some(function(u) { return u.indexOf('/api/sessions/sess-A/workspace') >= 0; }),
     '调用了 PUT /api/sessions/{id}/workspace');
  ok(globalThis.__fetchBodies.some(function(x) { return x.body.indexOf('gamma') >= 0; }),
     '请求体里带上了目标工作区');

  // ==================== 2.5 模型下载：前台进度条 ====================
  console.log('--- 模型下载：前台的进度条 ---');
  globalThis.__localStatus = {
    phase: 'downloading', downloadingFile: 'lion-merged-IQ4_XS.gguf',
    modelFile: 'lion-merged-IQ4_XS.gguf', modelInstalled: false, running: false,
    downloadBytes: 2100 * 1048576, downloadTotal: 4870 * 1048576
  };
  App._dlPrev = null;
  App._dlWasRunning = false;
  App.tickLocal();
  for (var __t = 0; __t < 8; __t++) { await tick(); }
  var dlBar = document.getElementById('dlBar');
  ok(dlBar.style.display === 'block', '★ 下载时前台出现进度条（不用去设置页找）');
  ok(document.getElementById('dlFill').style.width === '43%',
     '★ 进度条宽度跟着百分比走（2100/4870 MB → 43%）',
     String(document.getElementById('dlFill').style.width));
  ok(document.getElementById('dlPct').textContent === '43%', '★ 右边有百分比数字',
     String(document.getElementById('dlPct').textContent));
  ok(document.getElementById('dlText').textContent.indexOf('lion-merged-IQ4_XS.gguf') >= 0,
     '进度条上写着正在下哪一份', String(document.getElementById('dlText').textContent));
  ok(document.getElementById('dlSpeed').textContent.indexOf('2.05 GB') >= 0,
     '有"已下 / 总量"', String(document.getElementById('dlSpeed').textContent));

  // 下完：闪一句完成
  globalThis.__localStatus = {
    phase: 'idle', downloadingFile: '', modelFile: 'lion-merged-IQ4_XS.gguf',
    modelInstalled: true, running: false, downloadBytes: 5105000000, downloadTotal: 5105000000
  };
  App.tickLocal();
  for (var __t = 0; __t < 8; __t++) { await tick(); }
  ok(document.getElementById('dlIcon').textContent === '✓', '★ 下完在条上闪一句"完成"');
  ok(document.getElementById('dlText').textContent.indexOf('下载完成') >= 0, '完成文案在',
     String(document.getElementById('dlText').textContent));
  ok(document.getElementById('dlFill').style.width === '100%', '完成时进度条是满的');

  // 失败也要说清楚
  globalThis.__localStatus = {
    phase: 'failed', modelFile: 'lion-merged-IQ4_XS.gguf', modelInstalled: false,
    running: false, lastError: '下载模型失败：HTTP 403'
  };
  App._dlWasRunning = true;
  App.tickLocal();
  for (var __t = 0; __t < 8; __t++) { await tick(); }
  ok(document.getElementById('dlIcon').textContent === '✗', '★ 下载失败时条上打叉');
  ok(document.getElementById('dlText').textContent.indexOf('HTTP 403') >= 0, '失败原因写在条上',
     String(document.getElementById('dlText').textContent));

  // 设置页：正在下的那一行也带小进度条（单独渲染一次，免得影响上面的按钮断言）
  var __savedModels = globalThis.__modelsData;
  globalThis.__modelsData = __savedModels.map(function(m) {
    return m.file === 'lion-merged-IQ4_XS.gguf'
      ? { file: m.file, label: m.label, sizeGb: m.sizeGb, note: m.note,
          current: false, configured: false, downloaded: false, downloading: true, custom: false }
      : m;
  });
  App.renderLlamaTab();
  for (var __m = 0; __m < 8; __m++) { await tick(); }
  ok(document.getElementById('settingsContent').innerHTML
       .indexOf('dlrowfill_lion-merged-IQ4_XS.gguf') >= 0,
     '\u2605 \u8bbe\u7f6e\u9875\u91cc\u6b63\u5728\u4e0b\u7684\u90a3\u4e00\u884c\u4e5f\u6709\u5c0f\u8fdb\u5ea6\u6761');
  globalThis.__modelsData = __savedModels;

  // ==================== 2.6 前台「模型」面板（挑版本 / 点下载） ====================
  console.log('--- 前台模型面板 ---');
  App.modelsCache = [
    { file: 'lion-merged-Q8_0.gguf', label: 'Q8_0（默认）', sizeGb: 8.87, sizeText: '8.87 GB',
      custom: false, current: false, configured: false, downloaded: true, downloading: false },
    { file: 'lion-merged-Q4_K_M.gguf', label: 'Q4_K_M（平衡）', sizeGb: 5.24, sizeText: '5.24 GB',
      custom: false, current: false, configured: false, downloaded: false, downloading: false },
    { file: 'lion-merged-IQ4_XS.gguf', label: 'IQ4_XS（最快）', sizeGb: 4.87, sizeText: '4.87 GB',
      custom: false, current: true, configured: true, downloaded: false, downloading: false }
  ];
  App._modelPanelOpen = false;
  App._lastLocal = { phase: 'ready', running: true, modelFile: 'lion-merged-IQ4_XS.gguf',
                     downloadingFile: '', modelDir: 'D:\\models-here' };
  App.renderModelPanel();
  var mp = document.getElementById('modelPanel').innerHTML;
  ok(mp.indexOf('还没下载') >= 0, '★ 你选的那份没下载时，前台面板自动摊开并说清楚');
  ok(mp.indexOf('App.downloadLlamaModel') >= 0, '★ 前台就能点「下载」（不用翻设置）');
  ok(mp.indexOf('App.switchLlamaModel') >= 0, '前台也能直接「使用」别的版本');
  ok(mp.indexOf('模型目录') >= 0, '面板里写着模型目录');
  ok(mp.indexOf('App.openModelsDir') >= 0, '面板里有「打开文件夹」');

  // 正在下载：面板里就是那条进度条
  App._lastLocal = { phase: 'downloading', downloadingFile: 'lion-merged-IQ4_XS.gguf',
                     modelFile: 'lion-merged-IQ4_XS.gguf',
                     downloadBytes: 2100 * 1048576, downloadTotal: 4870 * 1048576,
                     modelDir: 'D:\\models-here' };
  App._dlSpeedTxt = '3.0 MB/s · 约 16 分钟后完成';
  App.renderModelPanel();
  mp = document.getElementById('modelPanel').innerHTML;
  ok(mp.indexOf('id="mpFill"') >= 0 && mp.indexOf('width:43%') >= 0,
     '★ 下载中，面板里就是那条进度条（43%）');
  ok(mp.indexOf('正在下载') >= 0, '面板抬头写着正在下载哪一份');
  ok(mp.indexOf('MB/s') >= 0, '面板上有速度和剩余时间');

  // 一切正常：收成一行，不留进度条
  App._lastLocal = { phase: 'ready', running: true, modelFile: 'lion-merged-Q8_0.gguf',
                     downloadingFile: '', modelDir: 'D:\\' };
  App.renderModelPanel();
  mp = document.getElementById('modelPanel').innerHTML;
  ok(mp.indexOf('本地模型：') >= 0 && mp.indexOf('正在用') >= 0, '一切正常时收成一行状态');
  ok(mp.indexOf('id="mpFill"') < 0, '不下载时不留进度条（别占地方）');
  App.modelsCache = globalThis.__modelsData;

  // ==================== 2.7 前台下载窗口 ====================
  console.log('--- 模型下载：前台窗口 ---');
  App.modelsCache = [
    { file: 'lion-merged-Q8_0.gguf', label: 'Q8_0（默认）', sizeGb: 8.87, sizeText: '8.87 GB',
      custom: false, current: false, configured: false, downloaded: true, downloading: false },
    { file: 'lion-merged-IQ4_XS.gguf', label: 'IQ4_XS（最快）', sizeGb: 4.87, sizeText: '4.87 GB',
      custom: false, current: false, configured: true, downloaded: false, downloading: false }
  ];
  App._dlgFile = null;
  App._lastLocal = { phase: 'idle', modelInstalled: false, modelFile: 'lion-merged-IQ4_XS.gguf' };
  App.switchLlamaModel('lion-merged-IQ4_XS.gguf');       // 没下载 → 应该弹窗口
  for (var __d = 0; __d < 8; __d++) { await tick(); }
  ok(document.getElementById('modalTitle').textContent.indexOf('模型下载') >= 0,
     '★ 点「使用」弹的是软件自己的下载窗口（不是浏览器原生提示）');
  var dlgBody = document.getElementById('modalBody').innerHTML;
  ok(dlgBody.indexOf('lion-merged-IQ4_XS.gguf') >= 0 && dlgBody.indexOf('id="dlgFill"') >= 0,
     '窗口里有文件名和进度条');
  ok(dlgBody.indexOf('4.87 GB') >= 0, '窗口里写着体积');

  // 下载中：窗口里的进度跟着走
  App._lastLocal = { phase: 'downloading', downloadingFile: 'lion-merged-IQ4_XS.gguf',
                     modelFile: 'lion-merged-IQ4_XS.gguf',
                     downloadBytes: 2100 * 1048576, downloadTotal: 4870 * 1048576 };
  App._dlSpeedTxt = '3.0 MB/s · 约 16 分钟后完成';
  App.updateDownloadDialog(App._lastLocal);
  ok(document.getElementById('dlgFill').style.width === '43%',
     '★ 窗口里的进度条跟着走（2100/4870 → 43%）',
     String(document.getElementById('dlgFill').style.width));
  ok(document.getElementById('dlgPct').textContent === '43%', '窗口里有百分比');
  ok(document.getElementById('dlgBytes').textContent.indexOf('2.05 GB') >= 0, '窗口里有已下/总量',
     String(document.getElementById('dlgBytes').textContent));
  ok(document.getElementById('dlgSpeed').textContent.indexOf('MB/s') >= 0, '窗口里有速度和剩余时间');
  ok(document.getElementById('dlgState').textContent.indexOf('正在下载') >= 0, '窗口里明说正在下载');

  // 下完：窗口变成"完成 + 立即使用"
  App.modelsCache = [
    { file: 'lion-merged-IQ4_XS.gguf', label: 'IQ4_XS（最快）', sizeGb: 4.87, sizeText: '4.87 GB',
      custom: false, current: false, configured: true, downloaded: true, downloading: false }
  ];
  App._lastLocal = { phase: 'idle', modelInstalled: true, modelFile: 'lion-merged-IQ4_XS.gguf' };
  App.updateDownloadDialog(App._lastLocal);
  ok(document.getElementById('dlgState').textContent.indexOf('下载完成') >= 0, '★ 下完窗口说"下载完成"',
     String(document.getElementById('dlgState').textContent));
  ok(document.getElementById('dlgBtns').innerHTML.indexOf('立即使用') >= 0, '★ 下完给「立即使用」按钮');
  ok(document.getElementById('dlgFill').style.width === '100%', '完成时进度条满格');

  // 失败：窗口里给原因和重试
  App.modelsCache = [
    { file: 'lion-merged-IQ4_XS.gguf', label: 'IQ4_XS（最快）', sizeGb: 4.87, sizeText: '4.87 GB',
      custom: false, current: false, configured: true, downloaded: false, downloading: false }
  ];
  App._lastLocal = { phase: 'failed', lastError: '\u4e0b\u8f7d\u6a21\u578b\u5931\u8d25\uff1aHTTP 403' };
  App.updateDownloadDialog(App._lastLocal);
  ok(document.getElementById('dlgState').textContent.indexOf('HTTP 403') >= 0, '★ 失败时窗口给出原因',
     String(document.getElementById('dlgState').textContent));
  ok(document.getElementById('dlgBtns').innerHTML.indexOf('重试') >= 0, '失败时给「重试」');
  App.collapseDownloadDialog();
  App.modelsCache = globalThis.__modelsData;

  // ==================== 2.8 历史渲染：工具行统一格式 ====================
  console.log('--- 历史消息：工具行格式 ---');
  globalThis.__historyData = [
    { role: 'user', content: '把所有工具都调用一遍' },
    { role: 'assistant', content: '', toolCalls: [
        { id: 'c1', name: 'list_directory', arguments: '{}' },
        { id: 'c2', name: 'system_info', arguments: '{}' }] },
    { role: 'tool', toolName: 'list_directory', content: 'a.txt\nb.txt' },
    { role: 'tool', toolName: 'system_info', content: '工具执行错误: 取不到' },
    { role: 'assistant', content: '都试完了。', toolCalls: [] },
    { role: 'user', content: '【系统提示】你上一次没有输出任何内容。' }
  ];
  // 抓一下 appendMsg 造出来的那些块（假 DOM 的 appendChild 是空实现，只能自己收）
  var __made = [];
  var __origCreate = document.createElement;
  document.createElement = function(t) { var e = __origCreate(t); __made.push(e); return e; };
  App.loadHistory('sess-x');
  for (var __h = 0; __h < 8; __h++) { await tick(); }
  document.createElement = __origCreate;
  var allHtml = __made.map(function(e) { return String(e.innerHTML || ''); }).join('\n');
  ok(allHtml.indexOf('🔧 调用工具：') >= 0 && allHtml.indexOf('list_directory') >= 0,
     '★ 历史里的工具调用也是"🔧 调用工具：X …"这种一行式');
  ok(allHtml.indexOf('✅ list_directory 完成') >= 0, '★ 成功的工具是"✅ X 完成"');
  ok(allHtml.indexOf('❌ system_info 失败') >= 0, '★ 失败的工具是"❌ X 失败"',
     allHtml.indexOf('system_info') >= 0 ? '有 system_info 行但不是失败样式' : '没有 system_info');
  ok(allHtml.indexOf('工具结果: ') < 0, '不再出现旧的"🔧 工具结果: X"那种行');
  ok(allHtml.indexOf('【系统提示】') < 0, '★ 【系统提示】不再显示在对话里');
  var toolBlocks = __made.filter(function(e) {
    return String(e.innerHTML || '').indexOf('调用工具') >= 0
        || String(e.innerHTML || '').indexOf('完成</span>') >= 0; });
  ok(toolBlocks.length >= 3 && toolBlocks.every(function(e) {
       return String(e.innerHTML || '').indexOf('msg-avatar"></div>') >= 0; }),
     '工具行上面不顶 🦁（头像留空）');

  // ==================== 3. llama.cpp 设置页 ====================
  console.log('--- 设置页：本地模型 / llama.cpp ---');
  var __lerr = null;
  try { App.renderLlamaTab(); } catch (e) { __lerr = e; }
  for (var __i = 0; __i < 8; __i++) { await tick(); }
  var cfg = document.getElementById('settingsContent').innerHTML;
  if (__lerr) { console.log('    renderLlamaTab 抛异常: ' + __lerr); }
  console.log('    settingsContent 长度 = ' + cfg.length);
  var needIds = ['llama_ctxSize','llama_ngl','llama_kvCacheTypeK','llama_kvCacheTypeV',
                 'llama_parallelSlots','llama_flashAttn','llama_threads','llama_batchSize',
                 'llama_ubatchSize','llama_maxPredict','llama_temperature','llama_topP',
                 'llama_topK','llama_minP','llama_repeatPenalty','llama_repeatLastN',
                 'llama_seed','llama_host','llama_port','llama_extraArgs'];
  var missing = needIds.filter(function(id) { return cfg.indexOf(id) < 0; });
  ok(missing.length === 0, '所有 llama.cpp 参数输入框都在', '缺: ' + missing.join(','));
  ok(cfg.indexOf('262144') >= 0, '读到了当前上下文长度（262144）');
  ok(cfg.indexOf('q4_0') >= 0, '读到了 KV 缓存类型（q4_0）');
  ok(cfg.indexOf('lion-merged-Q4_K_M.gguf') >= 0 || cfg.indexOf('Q4_K_M') >= 0, '列出了模型版本');
  ok(cfg.indexOf('App.switchLlamaModel') >= 0, '非当前版本有「使用」按钮（接了 App.switchLlamaModel）');
  ok(cfg.indexOf('App.downloadLlamaModel') >= 0, '★ 没下载的那份有「下载」按钮（只下不切换）');
  ok(cfg.indexOf('下载') >= 0 && cfg.indexOf('用它') >= 0,
     '★ 按钮文案是人话：「下载」「用它」');
  ok(cfg.indexOf('D:\\models-here') >= 0
     && (cfg.indexOf('模型文件放在') >= 0 || cfg.indexOf('模型目录') >= 0),
     '★ 把模型目录显示出来了（用户要知道往哪塞 GGUF）');
  ok(cfg.indexOf('App.openModelsDir') >= 0, '有「打开文件夹」按钮');
  ok(cfg.indexOf('my-own-7b-Q4_K_M.gguf') >= 0, '★ 自己塞进去的 GGUF 会被列出来');
  // 卡片式：顶部一行说清当前用哪份；每张卡只有一个主动作
  ok(cfg.indexOf('当前在用') >= 0, '★ 顶部一句话说清“当前在用哪份”');
  ok(cfg.indexOf('App.openModelsDir') >= 0 && cfg.indexOf('打开模型文件夹') >= 0,
     '有「打开模型文件夹」按钮');
  ok(cfg.indexOf('✓ 正在使用') >= 0, '正在用的那份标“✓ 正在使用”');
  // 每张卡只有一个主动作：未下载的那张只能有「下载」，不能同时出现「用它」
  // 每张卡只有一个主动作：未下载的那张只给「下载」，不给「用它」；
  // 直接查那两个按钮字符串（拼出来，免得跟转义较劲）
  var __q = String.fromCharCode(39);            // 反斜杠 + 单引号
  var __dlBtn = 'App.downloadLlamaModel(' + __q + 'lion-merged-IQ4_XS.gguf' + __q + ')';
  var __useBtn = 'App.switchLlamaModel(' + __q + 'lion-merged-IQ4_XS.gguf' + __q + ')';
  ok(cfg.indexOf(__dlBtn) >= 0 && cfg.indexOf(__useBtn) < 0,
     '\u2605 \u672a\u4e0b\u8f7d\u7684\u90a3\u5f20\u5361\u53ea\u7ed9\u300c\u4e0b\u8f7d\u300d\u4e00\u4e2a\u52a8\u4f5c',
     '\u542b\u4e0b\u8f7d=' + (cfg.indexOf(__dlBtn) >= 0) + ' \u542b\u7528\u5b83=' + (cfg.indexOf(__useBtn) >= 0));
  ok(cfg.indexOf('llamaAdvanced') >= 0 && cfg.indexOf('display:none') >= 0,
     '★ 高级参数默认收起（设置页不再一眼望不到底）');
  ok(cfg.indexOf('App.toggleLlamaAdvanced') >= 0, '有展开/收起高级参数的入口');
  ok(cfg.indexOf('你自己放进') >= 0, '自己塞的那一组有单独标题');
  ok(cfg.indexOf('App.saveLlamaConfig') >= 0, '有"保存参数"按钮');
  ok(cfg.indexOf('App.restartLocalModel') >= 0, '有"重启本地模型"按钮');
  ok(cfg.indexOf('额外参数') >= 0, '有自由参数（能填任何这里没有的开关）');
  ok(cfg.indexOf('8.87') >= 0 && cfg.indexOf('5.24') >= 0, '每份模型都标了体积');

  // ==================== 4. 保存参数 ====================
  console.log('--- 保存参数 ---');
  globalThis.__fetchBodies = [];
  document.getElementById('llama_ctxSize').value = '8192';
  document.getElementById('llama_extraArgs').value = '--no-mmap';
  // 真浏览器里 select 会有选中项的值，假 DOM 得自己给
  document.getElementById('llama_flashAttn').value = 'true';
  App.saveLlamaConfig();
  await tick();
  var post = globalThis.__fetchBodies.filter(function(x) { return x.url.indexOf('/api/runtime/local/config') >= 0; })[0];
  ok(!!post, '保存时 POST 到了 /api/runtime/local/config');
  var body = post ? JSON.parse(post.body) : {};
  ok(body.ctxSize === '8192', '上下文长度的改动带上了');
  ok(body.extraArgs === '--no-mmap', '自由参数带上了');
  ok(body.flashAttn === true, 'flash attention 用布尔值传');
  ok(Object.keys(body).length >= 19, '参数项够全（' + Object.keys(body).length + ' 项）');

  // ==================== 5. 切模型 / 重启 ====================
  console.log('--- 切模型与重启 ---');
  globalThis.__fetchLog = []; globalThis.__fetchBodies = [];
  // 点「下载」只下不切换
  globalThis.__fetchLog = [];
  globalThis.__fetchBodies = [];
  App.downloadLlamaModel('lion-merged-IQ4_XS.gguf');
  await tick();
  ok(globalThis.__fetchLog.some(function(u) { return u.indexOf('/api/runtime/local/models/download') >= 0; }),
     '★ 点「下载」调用了 /api/runtime/local/models/download');
  ok(globalThis.__fetchBodies.some(function(x) { return x.body.indexOf('IQ4_XS') >= 0; }),
     '下载请求里带上了要下的文件名');

  // 打开模型目录
  globalThis.__fetchLog = [];
  App.openModelsDir();
  await tick();
  ok(globalThis.__fetchLog.some(function(u) { return u.indexOf('/api/runtime/local/models/open-dir') >= 0; }),
     '★ 「打开文件夹」调用了 open-dir');

  globalThis.__fetchLog = [];
  globalThis.__fetchBodies = [];
  App.switchLlamaModel('lion-merged-Q8_0.gguf');
  App.restartLocalModel();
  await tick();
  var sw = globalThis.__fetchBodies.filter(function(x) { return x.url.indexOf('/api/runtime/local/model') >= 0; })[0];
  ok(!!sw && JSON.parse(sw.body).file === 'lion-merged-Q8_0.gguf', '切模型把文件名发给后端了');
  ok(globalThis.__fetchLog.some(function(u) { return u.indexOf('/api/runtime/local/restart') >= 0; }),
     '重启本地模型调了 /api/runtime/local/restart');

  console.log('');
  if (__fails.length) {
    console.log('结果：有失败项 -> ' + __fails.join(' | '));
    process.exit(1);
  }
  console.log('结果：全部通过');
 } catch (e) {
  console.log('测试脚本抛异常: ' + (e && e.stack ? e.stack : e));
  console.log('结果：有失败项');
  process.exit(1);
 }
})();
'''

with tempfile.TemporaryDirectory() as tmp:
    js = os.path.join(tmp, 'ui_features_check.js')
    io.open(js, 'w', encoding='utf-8', newline='\n').write(PREAMBLE + app_js + TESTS)
    r = subprocess.run(['node', js], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=120)

print(r.stdout.strip())
if r.stderr.strip():
    print('--- stderr ---')
    print(r.stderr.strip()[:1200])

# 静态补充：页签入口与函数定义
static_ok = True
for need in ['setTabLlama', "settingsTab('llama')", 'renderLlamaTab:', 'renderSessionTabs:',
             'moveSession:', 'toggleWsTab:', 'saveLlamaConfig:', 'switchLlamaModel:',
             'restartLocalModel:']:
    found = need in app_js or need.replace("settingsTab('llama')", "settingsTab(\\'llama\\')") in app_js
    if not found:
        print('  [FAIL] index.html 里缺少 %s' % need)
        static_ok = False
if static_ok:
    print('  [OK]   设置页签入口与新增函数定义都在 index.html 里')

# 模型下载走自己的窗口，不再弹浏览器原生提示
if "confirm('\u7528 '" in app_js or 'confirm("\u7528 "' in app_js:
    print('  [FAIL] 模型这条路还在用浏览器原生 confirm')
    static_ok = False
if 'openDownloadDialog' not in app_js or 'updateDownloadDialog' not in app_js:
    print('  [FAIL] 前台下载窗口的函数不在')
    static_ok = False

# 模式：只剩标准和极简（PTC / 创造不再给用户选）
# 注意模式按钮在 HTML 里、不在 <script> 里，所以要查 html 全文而不是 app_js
mode_btns = re.findall('data-mode="([A-Z]+)"', html)
if mode_btns == ['STANDARD', 'MINIMAL']:
    print('  [OK]   模式按钮只剩「标准」「极简」两个')
else:
    print('  [FAIL] 模式按钮应该只有 STANDARD / MINIMAL，实际：%s' % mode_btns)
    static_ok = False
for gone in ('data-mode="PTC"', 'data-mode="CREATIVE"'):
    if gone in html:
        print('  [FAIL] index.html 里还有 %s' % gone)
        static_ok = False
if 'App.setMode' not in html:
    print('  [FAIL] 模式按钮没接 App.setMode')
    static_ok = False

sys.exit(0 if (r.returncode == 0 and static_ok) else 1)

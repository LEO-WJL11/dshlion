/**
 * 文件浏览器组件 - 目录选择器
 */
const FileBrowser = {
    currentPath: '',
    selectedPath: null,
    showFiles: false,
    onSelectCallback: null,

    open(onSelect, options) {
        this.onSelectCallback = onSelect;
        this.showFiles = (options && options.showFiles) || false;
        this.currentPath = (options && options.initialPath) || '';
        this.selectedPath = null;

        // 移除旧弹窗
        var old = document.getElementById('fbOverlay');
        if (old) old.remove();

        // 创建遮罩
        var overlay = document.createElement('div');
        overlay.id = 'fbOverlay';
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);z-index:9999;display:flex;align-items:center;justify-content:center;';

        // 创建弹窗
        var box = document.createElement('div');
        box.style.cssText = 'background:#1e1e3a;border:1px solid #2d2d4a;border-radius:12px;width:750px;max-height:80vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,0.5);';

        // 头部
        var header = document.createElement('div');
        header.style.cssText = 'padding:16px 20px;border-bottom:1px solid #2d2d4a;display:flex;align-items:center;justify-content:space-between;';
        header.innerHTML = '<span style="font-size:16px;font-weight:600;color:#e8e8f0;">📁 选择工作区目录</span>';
        var closeBtn = document.createElement('button');
        closeBtn.textContent = '✕';
        closeBtn.style.cssText = 'background:none;border:none;color:#a0a0b8;font-size:20px;cursor:pointer;padding:4px 8px;';
        closeBtn.onclick = function() { overlay.remove(); };
        header.appendChild(closeBtn);
        box.appendChild(header);

        // 路径栏
        var pathBar = document.createElement('div');
        pathBar.style.cssText = 'padding:8px 16px;display:flex;gap:8px;align-items:center;background:#1a1a2e;';
        
        var pathInput = document.createElement('input');
        pathInput.type = 'text';
        pathInput.id = 'fbPathInput';
        pathInput.style.cssText = 'flex:1;padding:6px 12px;background:#1e1e3a;border:1px solid #2d2d4a;border-radius:4px;color:#e8e8f0;font-family:monospace;font-size:13px;';
        pathInput.placeholder = '输入路径后回车...';

        var goBtn = document.createElement('button');
        goBtn.textContent = '前往';
        goBtn.style.cssText = 'padding:6px 12px;background:#6c5ce7;color:white;border:none;border-radius:4px;cursor:pointer;font-size:12px;';
        
        pathBar.appendChild(pathInput);
        pathBar.appendChild(goBtn);
        box.appendChild(pathBar);

        // 快捷按钮
        var quickBar = document.createElement('div');
        quickBar.style.cssText = 'padding:8px 16px;display:flex;gap:6px;flex-wrap:wrap;';
        var quickBtns = [
            {label: '💿 驱动器', action: 'drives'},
            {label: '🏠 用户目录', action: 'home'},
            {label: '🖥️ 桌面', action: 'desktop'},
            {label: '📄 文档', action: 'documents'},
            {label: '⬆️ 上级', action: 'up'}
        ];
        quickBtns.forEach(function(qb) {
            var btn = document.createElement('button');
            btn.textContent = qb.label;
            btn.style.cssText = 'padding:4px 10px;background:#1e1e3a;border:1px solid #2d2d4a;border-radius:4px;color:#a0a0b8;cursor:pointer;font-size:12px;';
            btn.onmouseenter = function() { this.style.borderColor = '#6c5ce7'; this.style.color = '#e8e8f0'; };
            btn.onmouseleave = function() { this.style.borderColor = '#2d2d4a'; this.style.color = '#a0a0b8'; };
            btn.setAttribute('data-action', qb.action);
            quickBar.appendChild(btn);
        });
        box.appendChild(quickBar);

        // 文件列表
        var fileList = document.createElement('div');
        fileList.id = 'fbFileList';
        fileList.style.cssText = 'flex:1;overflow-y:auto;min-height:250px;max-height:400px;background:#1a1a2e;margin:0 16px;border:1px solid #2d2d4a;border-radius:4px;';
        box.appendChild(fileList);

        // 底部：选中路径 + 按钮
        var footer = document.createElement('div');
        footer.style.cssText = 'padding:12px 20px;border-top:1px solid #2d2d4a;display:flex;align-items:center;gap:12px;';
        
        var selectedDisplay = document.createElement('span');
        selectedDisplay.id = 'fbSelectedPath';
        selectedDisplay.style.cssText = 'flex:1;font-family:monospace;font-size:13px;color:#6c5ce7;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        selectedDisplay.textContent = '未选择';

        var cancelBtn = document.createElement('button');
        cancelBtn.textContent = '取消';
        cancelBtn.style.cssText = 'padding:8px 20px;background:transparent;color:#a0a0b8;border:1px solid #2d2d4a;border-radius:6px;cursor:pointer;font-size:13px;';
        cancelBtn.onclick = function() { overlay.remove(); };

        var confirmBtn = document.createElement('button');
        confirmBtn.textContent = '确认选择';
        confirmBtn.id = 'fbConfirmBtn';
        confirmBtn.style.cssText = 'padding:8px 20px;background:#6c5ce7;color:white;border:none;border-radius:6px;cursor:pointer;font-size:13px;';
        confirmBtn.disabled = true;
        confirmBtn.style.opacity = '0.5';

        footer.appendChild(selectedDisplay);
        footer.appendChild(cancelBtn);
        footer.appendChild(confirmBtn);
        box.appendChild(footer);

        overlay.appendChild(box);
        document.body.appendChild(overlay);

        // === 事件绑定 ===
        var self = this;

        // 点击遮罩关闭
        overlay.onclick = function(e) {
            if (e.target === overlay) overlay.remove();
        };

        // 回车跳转
        pathInput.onkeydown = function(e) {
            if (e.key === 'Enter') self.browse(pathInput.value, fileList, selectedDisplay, confirmBtn);
        };
        goBtn.onclick = function() { self.browse(pathInput.value, fileList, selectedDisplay, confirmBtn); };

        // 快捷按钮
        quickBar.onclick = function(e) {
            var btn = e.target.closest('button');
            if (!btn) return;
            var action = btn.getAttribute('data-action');
            self.handleQuickAction(action, fileList, pathInput, selectedDisplay, confirmBtn);
        };

        // 确认按钮
        confirmBtn.onclick = function() {
            if (self.selectedPath && self.onSelectCallback) {
                self.onSelectCallback(self.selectedPath);
            }
            overlay.remove();
        };

        // 加载初始目录
        this.browse(this.currentPath || '', fileList, pathInput, selectedDisplay, confirmBtn);
    },

    handleQuickAction(action, fileList, pathInput, selectedDisplay, confirmBtn) {
        var self = this;
        if (action === 'drives') {
            this.loadDrives(fileList, pathInput, selectedDisplay, confirmBtn);
        } else if (action === 'home') {
            fetch('/api/filesystem/browse?path=')
                .then(function(r) { return r.json(); })
                .then(function(result) {
                    if (result.success && result.data) {
                        var entries = result.data;
                        if (Array.isArray(entries)) {
                            // 驱动器列表，找C盘然后进Users
                            self.browse('C:\\Users', fileList, pathInput, selectedDisplay, confirmBtn);
                        }
                    }
                });
        } else if (action === 'desktop') {
            this.browse('C:\\Users\\' + this.getUserName() + '\\Desktop', fileList, pathInput, selectedDisplay, confirmBtn);
        } else if (action === 'documents') {
            this.browse('C:\\Users\\' + this.getUserName() + '\\Documents', fileList, pathInput, selectedDisplay, confirmBtn);
        } else if (action === 'up') {
            if (this.currentPath) {
                var parent = this.currentPath.replace(/[\\\/][^\\\/]+[\\\/]?$/, '');
                if (parent && parent !== this.currentPath) {
                    this.browse(parent || 'C:\\', fileList, pathInput, selectedDisplay, confirmBtn);
                }
            }
        }
    },

    getUserName() {
        var match = this.currentPath && this.currentPath.match(/C:\\Users\\([^\\]+)/i);
        return match ? match[1] : '用户';
    },

    loadDrives(fileList, pathInput, selectedDisplay, confirmBtn) {
        var self = this;
        fetch('/api/filesystem/drives')
            .then(function(r) { return r.json(); })
            .then(function(result) {
                if (!result.success) { fileList.innerHTML = '<div style="padding:20px;text-align:center;color:#e17055;">加载失败</div>'; return; }
                fileList.innerHTML = '';
                pathInput.value = '此电脑';
                self.currentPath = '';
                result.data.forEach(function(d) {
                    var freeGB = (d.freeSpace / 1073741824).toFixed(1);
                    var totalGB = (d.totalSpace / 1073741824).toFixed(1);
                    var row = self.createRow('💿', d.label + ' (' + freeGB + 'GB可用 / ' + totalGB + 'GB)', true, function() {
                        self.browse(d.path, fileList, pathInput, selectedDisplay, confirmBtn);
                    });
                    fileList.appendChild(row);
                });
            })
            .catch(function(e) { fileList.innerHTML = '<div style="padding:20px;text-align:center;color:#e17055;">加载失败: ' + e.message + '</div>'; });
    },

    browse(path, fileList, pathInput, selectedDisplay, confirmBtn) {
        var self = this;
        fileList.innerHTML = '<div style="padding:30px;text-align:center;color:#6a6a80;">加载中...</div>';

        var url = '/api/filesystem/browse?path=' + encodeURIComponent(path || '') + '&showFiles=' + this.showFiles;
        fetch(url)
            .then(function(r) { return r.json(); })
            .then(function(result) {
                if (!result.success) {
                    fileList.innerHTML = '<div style="padding:20px;text-align:center;color:#e17055;">❌ ' + result.error + '</div>';
                    return;
                }

                var data = result.data;
                // 判断是驱动器列表还是目录内容
                if (Array.isArray(data)) {
                    // 驱动器列表
                    self.loadDrives(fileList, pathInput, selectedDisplay, confirmBtn);
                    return;
                }

                self.currentPath = data.currentPath;
                if (pathInput.tagName === 'INPUT') {
                    pathInput.value = data.currentPath;
                }

                fileList.innerHTML = '';

                // 上级目录
                if (data.parentPath) {
                    var upRow = self.createRow('📁', '.. (上级目录)', true, function() {
                        self.browse(data.parentPath, fileList, pathInput, selectedDisplay, confirmBtn);
                    });
                    fileList.appendChild(upRow);
                }

                // 条目列表
                if (data.entries && data.entries.length > 0) {
                    data.entries.forEach(function(entry) {
                        var icon = entry.directory ? '📁' : self.getFileIcon(entry.name);
                        var size = entry.directory ? '' : self.formatSize(entry.size);
                        var label = entry.name + (size ? '  (' + size + ')' : '');
                        var row = self.createRow(icon, label, entry.directory, function() {
                            if (entry.directory) {
                                self.browse(entry.path, fileList, pathInput, selectedDisplay, confirmBtn);
                            }
                        });
                        // 点击选中
                        row.onclick = function(e) {
                            // 取消其他选中
                            fileList.querySelectorAll('[data-selected="true"]').forEach(function(el) {
                                el.style.background = '';
                                el.setAttribute('data-selected', 'false');
                            });
                            row.style.background = 'rgba(108,92,231,0.2)';
                            row.setAttribute('data-selected', 'true');
                            self.selectedPath = entry.path;
                            selectedDisplay.textContent = entry.path;
                            if (selectedDisplay.tagName === 'INPUT') selectedDisplay.value = entry.path;
                            // 只有目录才能确认选择（工作区必须是目录）
                            if (entry.directory) {
                                confirmBtn.disabled = false;
                                confirmBtn.style.opacity = '1';
                            }
                        };
                        fileList.appendChild(row);
                    });
                } else if (!data.parentPath) {
                    fileList.innerHTML = '<div style="padding:30px;text-align:center;color:#6a6a80;">📂 空目录</div>';
                }
            })
            .catch(function(e) {
                fileList.innerHTML = '<div style="padding:20px;text-align:center;color:#e17055;">❌ ' + e.message + '</div>';
            });
    },

    createRow(icon, label, isDir, onDblClick) {
        var row = document.createElement('div');
        row.style.cssText = 'display:flex;align-items:center;gap:10px;padding:8px 12px;cursor:pointer;border-bottom:1px solid #252540;user-select:none;';
        row.innerHTML = '<span style="font-size:16px;width:22px;text-align:center;">' + icon + '</span>' +
                        '<span style="flex:1;font-size:13px;color:#e8e8f0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' + (isDir ? 'font-weight:500;' : '') + '">' + label + '</span>';
        row.onmouseenter = function() { if (this.getAttribute('data-selected') !== 'true') this.style.background = 'rgba(255,255,255,0.05)'; };
        row.onmouseleave = function() { if (this.getAttribute('data-selected') !== 'true') this.style.background = ''; };
        if (onDblClick) {
            row.ondblclick = onDblClick;
        }
        return row;
    },

    getFileIcon(name) {
        var ext = (name.split('.').pop() || '').toLowerCase();
        var map = {java:'☕',py:'🐍',js:'📜',ts:'📘',html:'🌐',css:'🎨',json:'📋',xml:'📄',md:'📝',txt:'📄',log:'📊',png:'🖼️',jpg:'🖼️',gif:'🖼️',svg:'🖼️',zip:'📦',rar:'📦',exe:'⚙️',pdf:'📕',doc:'📘',xls:'📗',mp3:'🎵',mp4:'🎬'};
        return map[ext] || '📄';
    },

    formatSize(bytes) {
        if (bytes == null) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
        return (bytes / 1073741824).toFixed(1) + ' GB';
    }
};

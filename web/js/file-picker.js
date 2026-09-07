/**
 * Windows原生文件选择器
 * 
 * 使用HTML5 <input type="file"> + webkitdirectory属性
 * 直接调用Windows系统文件选择对话框
 */
const FilePicker = {
    /**
     * 打开Windows原生目录选择对话框
     * @param {Function} onSelect - 选择回调，参数为选中的路径
     */
    selectDirectory(onSelect) {
        // 创建隐藏的input元素
        const input = document.createElement('input');
        input.type = 'file';
        input.webkitdirectory = true;  // 启用目录选择模式
        input.mozdirectory = true;     // Firefox兼容
        input.directory = true;        // 通用属性
        input.style.display = 'none';
        
        input.addEventListener('change', (e) => {
            const files = e.target.files;
            if (files && files.length > 0) {
                // 从第一个文件的路径提取目录路径
                const fullPath = files[0].webkitRelativePath || files[0].name;
                // 获取完整路径（需要从文件对象推断）
                const dirPath = this.extractDirPath(files);
                if (dirPath && onSelect) {
                    onSelect(dirPath);
                }
            }
            // 清理
            document.body.removeChild(input);
        });

        // 处理取消选择
        input.addEventListener('cancel', () => {
            document.body.removeChild(input);
        });

        document.body.appendChild(input);
        input.click();
    },

    /**
     * 打开Windows原生文件选择对话框
     * @param {Function} onSelect - 选择回调
     * @param {Object} options - 配置选项
     */
    selectFile(onSelect, options = {}) {
        const input = document.createElement('input');
        input.type = 'file';
        input.style.display = 'none';
        
        if (options.accept) {
            input.accept = options.accept;
        }
        if (options.multiple) {
            input.multiple = true;
        }

        input.addEventListener('change', (e) => {
            const files = e.target.files;
            if (files && files.length > 0) {
                if (options.multiple) {
                    const paths = Array.from(files).map(f => f.name);
                    onSelect(paths);
                } else {
                    onSelect(files[0].name);
                }
            }
            document.body.removeChild(input);
        });

        input.addEventListener('cancel', () => {
            document.body.removeChild(input);
        });

        document.body.appendChild(input);
        input.click();
    },

    /**
     * 打开Windows原生保存文件对话框
     * @param {Function} onSave - 保存回调
     * @param {Object} options - 配置选项
     */
    saveFile(onSave, options = {}) {
        // 使用Blob创建下载
        const input = document.createElement('input');
        input.type = 'file';
        input.nwsaveas = options.defaultName || 'untitled';  // NW.js保存对话框
        input.style.display = 'none';

        input.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                onSave(e.target.files[0].name);
            }
            document.body.removeChild(input);
        });

        document.body.appendChild(input);
        input.click();
    },

    /**
     * 从文件列表提取目录路径
     */
    extractDirPath(files) {
        // webkitRelativePath格式: "目录名/文件名"
        const firstPath = files[0].webkitRelativePath;
        if (firstPath) {
            // 提取顶级目录名
            const parts = firstPath.split('/');
            if (parts.length > 1) {
                // 尝试从File对象获取完整路径
                // 注意: 浏览器安全限制，无法直接获取完整路径
                // 但我们可以获取目录名
                return parts[0];
            }
        }
        
        // 备选方案: 使用File API的path属性（部分浏览器支持）
        if (files[0].path) {
            // Node.js/Electron环境
            const path = files[0].path;
            const lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return lastSep > 0 ? path.substring(0, lastSep) : path;
        }
        
        return null;
    },

    /**
     * 使用Windows原生对话框选择工作区目录
     * 集成方法，直接替换prompt()
     */
    selectWorkspace(currentPath, callback) {
        // 方案1: 使用Electron/NW.js的原生对话框
        if (typeof require !== 'undefined') {
            try {
                const { dialog } = require('electron').remote || require('@electron/remote');
                dialog.showOpenDialog({
                    title: '选择工作区目录',
                    defaultPath: currentPath || '',
                    properties: ['openDirectory', 'createDirectory']
                }).then(result => {
                    if (!result.canceled && result.filePaths.length > 0) {
                        callback(result.filePaths[0]);
                    }
                });
                return;
            } catch (e) {
                // 非Electron环境，继续
            }
        }

        // 方案2: 使用HTML5 webkitdirectory（触发Windows原生目录选择）
        this.selectDirectory((dirName) => {
            // 提示用户确认完整路径
            // 由于浏览器安全限制，webkitdirectory只返回相对路径
            // 需要用户输入基础路径或使用自定义文件浏览器作为后备
            callback(dirName);
        });
    }
};

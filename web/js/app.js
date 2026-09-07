/**
 * 主应用模块
 * 
 * 负责应用初始化、事件绑定、模型和思考等级管理
 */
const App = {
    currentMode: 'STANDARD',
    currentWorkspace: null,
    currentModel: null,
    availableModels: [],
    thinkingLevels: [],

    async init() {
        this.bindEvents();
        await this.loadInitialData();
        console.log('🦁 Lion-Code Agent Harness 已启动');
    },

    bindEvents() {
        // 新建会话
        document.getElementById('newSessionBtn').addEventListener('click', () => {
            if (!this.currentWorkspace) {
                alert('请先选择工作区');
                return;
            }
            SessionManager.createSession(this.currentWorkspace, this.currentMode);
        });

        // 选择工作区 → 后端弹Windows原生文件夹选择框
        document.getElementById('selectWorkspaceBtn').addEventListener('click', async function() {
            document.getElementById('selectWorkspaceBtn').textContent = '请在弹出的窗口中选择...';
            document.getElementById('selectWorkspaceBtn').disabled = true;
            try {
                var initial = App.currentWorkspace || '';
                var resp = await fetch('/api/native-dialog/select-directory?initialPath=' + encodeURIComponent(initial));
                var result = await resp.json();
                if (result.success && result.data) {
                    App.selectWorkspace(result.data);
                }
            } catch (e) {
                console.error('选择失败:', e);
            }
            document.getElementById('selectWorkspaceBtn').textContent = '选择';
            document.getElementById('selectWorkspaceBtn').disabled = false;
        });

        // 发送消息
        document.getElementById('sendBtn').addEventListener('click', () => Chat.sendMessage());
        document.getElementById('messageInput').addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                Chat.sendMessage();
            }
        });

        // 输入框自动调整高度
        document.getElementById('messageInput').addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = Math.min(this.scrollHeight, 200) + 'px';
        });

        // 侧边栏切换
        document.getElementById('toggleSidebar').addEventListener('click', () => {
            document.getElementById('sidebar').classList.toggle('open');
        });

        // 模式切换
        document.querySelectorAll('.mode-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                this.currentMode = btn.dataset.mode;
            });
        });

        // 模型选择变化时，动态加载思考等级
        document.getElementById('modelSelect').addEventListener('change', (e) => {
            this.onModelChange(e.target.value);
        });

        // 设置
        document.getElementById('settingsBtn').addEventListener('click', () => Settings.show());
        document.getElementById('pluginsBtn').addEventListener('click', () => this.showPlugins());

        // 设置标签切换
        document.querySelectorAll('.settings-tabs .tab-btn').forEach(btn => {
            btn.addEventListener('click', () => Settings.switchTab(btn.dataset.tab));
        });

        // 弹窗关闭
        document.querySelectorAll('.modal-close').forEach(btn => {
            btn.addEventListener('click', () => {
                btn.closest('.modal').classList.add('hidden');
            });
        });

        document.querySelectorAll('.modal-overlay').forEach(overlay => {
            overlay.addEventListener('click', () => {
                overlay.closest('.modal').classList.add('hidden');
            });
        });
    },

    async loadInitialData() {
        // 加载插件数量
        const pluginsResult = await API.plugins.list();
        if (pluginsResult.success) {
            const count = pluginsResult.data?.length || 0;
            document.getElementById('pluginCount').textContent = `已加载 ${count} 个插件`;
        }

        // 加载适配器状态
        const adapterResult = await API.chat.adapterStatus();
        if (adapterResult.success) {
            document.getElementById('adapterName').textContent = adapterResult.data?.name || '未知';
        }

        // 加载模型列表
        await this.loadModels();

        // 加载会话列表
        await SessionManager.loadSessions();
    },

    /**
     * 加载可用模型列表
     */
    async loadModels() {
        const result = await API.models.list();
        if (!result.success || !result.data) {
            console.warn('加载模型列表失败');
            return;
        }

        this.availableModels = result.data;
        this.renderModelSelect();
    },

    /**
     * 渲染模型下拉框
     */
    renderModelSelect() {
        const select = document.getElementById('modelSelect');
        select.innerHTML = '<option value="">选择模型...</option>';

        // 按来源分组：云端模型和本地模型
        const cloudModels = this.availableModels.filter(m => m.source !== 'LOCAL_GGUF');
        const localModels = this.availableModels.filter(m => m.source === 'LOCAL_GGUF');

        if (cloudModels.length > 0) {
            const cloudGroup = document.createElement('optgroup');
            cloudGroup.label = '☁️ 云端API模型';
            cloudModels.forEach(model => {
                const option = document.createElement('option');
                option.value = model.id;
                option.textContent = `${model.name} (${model.owner})`;
                option.dataset.supportsThinking = model.supportsThinking;
                cloudGroup.appendChild(option);
            });
            select.appendChild(cloudGroup);
        }

        if (localModels.length > 0) {
            const localGroup = document.createElement('optgroup');
            localGroup.label = '💻 本地GGUF模型';
            localModels.forEach(model => {
                const option = document.createElement('option');
                option.value = model.id;
                option.textContent = model.name;
                option.dataset.supportsThinking = model.supportsThinking;
                localGroup.appendChild(option);
            });
            select.appendChild(localGroup);
        }

        // 如果有模型，默认选择第一个
        if (this.availableModels.length > 0) {
            select.value = this.availableModels[0].id;
            this.onModelChange(this.availableModels[0].id);
        }
    },

    /**
     * 模型选择变化时的处理
     * 动态加载该模型支持的思考等级
     */
    async onModelChange(modelId) {
        if (!modelId) {
            this.renderThinkingLevelSelect([]);
            return;
        }

        this.currentModel = modelId;
        console.log(`模型已选择: ${modelId}`);

        // 从后端获取该模型的思考等级选项
        const result = await API.models.thinkingLevels(modelId);
        
        if (result.success) {
            this.thinkingLevels = result.data || [];
        } else {
            console.warn('获取思考等级失败:', result.error);
            this.thinkingLevels = [];
        }

        // 渲染思考等级下拉框
        this.renderThinkingLevelSelect(this.thinkingLevels);
    },

    /**
     * 渲染思考等级下拉框
     * 根据模型支持的思考等级动态生成选项
     */
    renderThinkingLevelSelect(levels) {
        const select = document.getElementById('thinkingLevel');
        select.innerHTML = '';

        if (!levels || levels.length === 0) {
            // 不支持思考等级的模型，禁用选择框
            select.innerHTML = '<option value="">不支持</option>';
            select.disabled = true;
            select.title = '当前模型不支持思考等级设置';
            return;
        }

        // 启用选择框
        select.disabled = false;
        select.title = '';

        // 添加选项
        levels.forEach(level => {
            const option = document.createElement('option');
            option.value = level.code;
            option.textContent = level.displayName;
            option.title = level.description;
            if (level.isDefault) {
                option.selected = true;
            }
            select.appendChild(option);
        });

        // 添加思考等级说明
        const selectedLevel = levels.find(l => l.isDefault) || levels[0];
        if (selectedLevel) {
            select.title = `${selectedLevel.displayName}: ${selectedLevel.description} (${selectedLevel.tokenBudget} tokens)`;
        }
    },

    async selectWorkspace(path) {
        const result = await API.workspaces.register(path);
        if (result.success) {
            this.currentWorkspace = path;
            document.getElementById('currentWorkspace').textContent = path;
            document.querySelector('.workspace-path').textContent = path;
        } else {
            alert(result.error || '工作区注册失败');
        }
    },

    async showPlugins() {
        document.getElementById('pluginModal').classList.remove('hidden');
        const result = await API.plugins.list();
        const container = document.getElementById('pluginList');

        if (!result.success || !result.data) {
            container.innerHTML = '<div class="empty-hint">加载失败</div>';
            return;
        }

        // 按类型分组
        const skills = result.data.filter(p => p.type === 'SKILL');
        const tools = result.data.filter(p => p.type === 'TOOL');

        container.innerHTML = `
            <div class="plugin-section">
                <h3 style="margin-bottom: 12px; color: var(--dsw-accent-primary);">📚 技能插件 (${skills.length})</h3>
                ${skills.map(p => this.renderPluginCard(p)).join('')}
            </div>
            <div class="plugin-section" style="margin-top: 24px;">
                <h3 style="margin-bottom: 12px; color: var(--dsw-accent-info);">🔧 工具插件 (${tools.length})</h3>
                ${tools.map(p => this.renderPluginCard(p)).join('')}
            </div>
        `;
    },

    renderPluginCard(plugin) {
        const icon = plugin.type === 'SKILL' ? '📚' : '🔧';
        return `
            <div class="plugin-card">
                <div class="plugin-icon">${icon}</div>
                <div class="plugin-info">
                    <div class="plugin-name">${plugin.name}</div>
                    <div class="plugin-desc">${plugin.description}</div>
                </div>
                <span class="plugin-type">${plugin.type === 'SKILL' ? '技能' : '工具'}</span>
            </div>
        `;
    }
};

// 启动应用
document.addEventListener('DOMContentLoaded', () => App.init());

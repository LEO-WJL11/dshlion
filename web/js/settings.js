/**
 * 设置模块
 */
const Settings = {
    providers: [],
    selectedProvider: null,

    async show() {
        document.getElementById('settingsModal').classList.remove('hidden');
        await this.loadProviders();
        this.renderProvidersTab();
    },

    hide() {
        document.getElementById('settingsModal').classList.add('hidden');
    },

    async loadProviders() {
        const result = await API.models.providers();
        if (result.success) {
            this.providers = result.data || [];
        }
    },

    renderProvidersTab() {
        const content = document.getElementById('settingsContent');
        content.innerHTML = `
            <div class="providers-list">
                <p style="color: var(--dsw-text-secondary); margin-bottom: 16px;">
                    选择API提供商，填入API-Key后自动获取可用模型列表。
                </p>
                <div class="form-group">
                    <label class="form-label">选择提供商</label>
                    <select id="providerSelect" class="form-select">
                        <option value="">-- 选择提供商 --</option>
                        ${this.providers.map(p => `
                            <option value="${p.providerId}">${p.displayName}</option>
                        `).join('')}
                    </select>
                </div>
                <div id="providerConfig" class="provider-config" style="display:none;">
                    <div class="form-group">
                        <label class="form-label">Base URL</label>
                        <input type="text" id="providerBaseUrl" class="form-input" readonly>
                    </div>
                    <div class="form-group">
                        <label class="form-label">API Key</label>
                        <input type="password" id="providerApiKey" class="form-input" placeholder="输入API Key...">
                    </div>
                    <div class="form-group" id="tokenPlanGroup" style="display:none;">
                        <label class="form-label">端点类型</label>
                        <div style="display: flex; gap: 8px;">
                            <button class="btn btn-ghost btn-sm endpoint-btn active" data-type="standard">普通按量</button>
                            <button class="btn btn-ghost btn-sm endpoint-btn" data-type="token-plan">Token-Plan</button>
                        </div>
                    </div>
                    <button id="fetchModelsBtn" class="btn btn-primary">获取模型列表</button>
                    <div id="modelList" class="model-list" style="margin-top: 16px;"></div>
                </div>
            </div>
        `;

        // 绑定事件
        document.getElementById('providerSelect').addEventListener('change', (e) => {
            this.selectProvider(e.target.value);
        });

        document.getElementById('fetchModelsBtn')?.addEventListener('click', () => {
            this.fetchModels();
        });
    },

    selectProvider(providerId) {
        const provider = this.providers.find(p => p.providerId === providerId);
        if (!provider) return;

        this.selectedProvider = provider;
        document.getElementById('providerConfig').style.display = 'block';
        document.getElementById('providerBaseUrl').value = provider.standardBaseUrl;

        // Token-Plan切换
        const tpGroup = document.getElementById('tokenPlanGroup');
        if (provider.supportsTokenPlan) {
            tpGroup.style.display = 'block';
        } else {
            tpGroup.style.display = 'none';
        }
    },

    async fetchModels() {
        // 简化实现：显示提示
        const list = document.getElementById('modelList');
        list.innerHTML = '<p style="color: var(--dsw-text-secondary);">模型获取功能需要后端配置完成后使用。</p>';
    },

    showModelsTab() {
        const content = document.getElementById('settingsContent');
        content.innerHTML = `
            <div class="models-management">
                <p style="color: var(--dsw-text-secondary); margin-bottom: 16px;">
                    管理可用模型池，勾选需要启用的模型。
                </p>
                <div id="modelPool" class="model-pool">
                    <div class="empty-hint">请先在API提供商中获取模型列表</div>
                </div>
            </div>
        `;
    },

    showAdaptersTab() {
        const content = document.getElementById('settingsContent');
        content.innerHTML = `
            <div class="adapters-management">
                <p style="color: var(--dsw-text-secondary); margin-bottom: 16px;">
                    管理模型协议适配器，支持OpenAI兼容和Anthropic原生接口。
                </p>
                <div class="adapter-cards">
                    <div class="plugin-card">
                        <div class="plugin-icon">🔗</div>
                        <div class="plugin-info">
                            <div class="plugin-name">OpenAI兼容适配器</div>
                            <div class="plugin-desc">支持所有OpenAI兼容接口服务商</div>
                        </div>
                        <span class="plugin-type">默认</span>
                    </div>
                    <div class="plugin-card">
                        <div class="plugin-icon">🧠</div>
                        <div class="plugin-info">
                            <div class="plugin-name">Anthropic Claude适配器</div>
                            <div class="plugin-desc">Anthropic Claude原生接口</div>
                        </div>
                        <span class="plugin-type">可用</span>
                    </div>
                </div>
            </div>
        `;
    },

    showAboutTab() {
        const content = document.getElementById('settingsContent');
        content.innerHTML = `
            <div class="about-section" style="text-align: center; padding: 32px;">
                <div style="font-size: 48px; margin-bottom: 16px;">🦁</div>
                <h3 style="font-size: 20px; margin-bottom: 8px;">Lion-Code Agent Harness</h3>
                <p style="color: var(--dsw-text-secondary); margin-bottom: 24px;">v1.0.0-SNAPSHOT</p>
                <p style="color: var(--dsw-text-secondary); font-size: 13px;">
                    本地Agent运行时框架<br>
                    超越DeepSeek-Harness<br><br>
                    核心特性：<br>
                    • 51个插件（4 Skill + 47 Tool）<br>
                    • 流式工具执行<br>
                    • 双协议适配器热切换<br>
                    • 事件溯源日志<br>
                    • 四种工作模式<br>
                    • 三级工作区权限
                </p>
            </div>
        `;
    },

    switchTab(tab) {
        document.querySelectorAll('.settings-tabs .tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === tab);
        });

        switch (tab) {
            case 'providers': this.renderProvidersTab(); break;
            case 'models': this.showModelsTab(); break;
            case 'adapters': this.showAdaptersTab(); break;
            case 'about': this.showAboutTab(); break;
        }
    }
};

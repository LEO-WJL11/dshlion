/**
 * 聊天模块
 */
const Chat = {
    isProcessing: false,

    async sendMessage() {
        const input = document.getElementById('messageInput');
        const message = input.value.trim();
        if (!message || this.isProcessing) return;

        if (!SessionManager.currentSession) {
            alert('请先创建会话');
            return;
        }

        const model = document.getElementById('modelSelect').value;
        const thinkingLevel = document.getElementById('thinkingLevel').value;

        // 显示用户消息
        this.appendMessage('user', message);
        input.value = '';
        input.style.height = 'auto';

        // 显示加载状态
        this.isProcessing = true;
        const loadingId = this.appendLoading();

        try {
            const result = await API.chat.send(
                SessionManager.currentSession.sessionId,
                message, model, thinkingLevel
            );

            this.removeLoading(loadingId);

            if (result.success) {
                this.appendMessage('assistant', result.data);
            } else {
                this.appendMessage('assistant', `❌ 错误: ${result.error}`);
            }
        } catch (error) {
            this.removeLoading(loadingId);
            this.appendMessage('assistant', `❌ 请求失败: ${error.message}`);
        } finally {
            this.isProcessing = false;
        }
    },

    appendMessage(role, content) {
        const area = document.getElementById('messageArea');
        
        // 移除欢迎屏幕
        const welcome = area.querySelector('.welcome-screen');
        if (welcome) welcome.remove();

        const avatar = role === 'user' ? '👤' : '🦁';
        const formattedContent = this.formatContent(content);

        const messageEl = document.createElement('div');
        messageEl.className = `message ${role}`;
        messageEl.innerHTML = `
            <div class="message-avatar">${avatar}</div>
            <div class="message-content">${formattedContent}</div>
        `;

        area.appendChild(messageEl);
        area.scrollTop = area.scrollHeight;
    },

    appendLoading() {
        const area = document.getElementById('messageArea');
        const id = 'loading-' + Date.now();
        const el = document.createElement('div');
        el.id = id;
        el.className = 'message assistant';
        el.innerHTML = `
            <div class="message-avatar">🦁</div>
            <div class="message-content">
                <span class="typing-indicator">思考中</span>
            </div>
        `;
        area.appendChild(el);
        area.scrollTop = area.scrollHeight;
        return id;
    },

    removeLoading(id) {
        const el = document.getElementById(id);
        if (el) el.remove();
    },

    formatContent(content) {
        if (!content) return '';
        // 基本的Markdown格式化
        return content
            .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code class="language-$1">$2</code></pre>')
            .replace(/`([^`]+)`/g, '<code>$1</code>')
            .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
            .replace(/\n/g, '<br>');
    },

    showWelcome() {
        const area = document.getElementById('messageArea');
        area.innerHTML = `
            <div class="welcome-screen">
                <div class="welcome-icon">🦁</div>
                <h1>Lion-Code Agent Harness</h1>
                <p class="welcome-desc">本地Agent运行时框架，超越DeepSeek-Harness</p>
                <div class="welcome-features">
                    <div class="feature-card">
                        <span class="feature-icon">🔧</span>
                        <span class="feature-title">51个插件</span>
                        <span class="feature-desc">4个Skill技能包 + 47个工具插件</span>
                    </div>
                    <div class="feature-card">
                        <span class="feature-icon">⚡</span>
                        <span class="feature-title">流式执行</span>
                        <span class="feature-desc">识别到工具调用就立即执行</span>
                    </div>
                    <div class="feature-card">
                        <span class="feature-icon">🔄</span>
                        <span class="feature-title">双协议适配</span>
                        <span class="feature-desc">OpenAI兼容 + Anthropic原生</span>
                    </div>
                    <div class="feature-card">
                        <span class="feature-icon">📝</span>
                        <span class="feature-title">事件溯源</span>
                        <span class="feature-desc">完整记录每轮思考和工具调用</span>
                    </div>
                </div>
            </div>
        `;
    },

    loadHistory(sessionId) {
        // 加载历史消息（简化实现）
        this.showWelcome();
    }
};

/**
 * 会话管理模块
 */
const SessionManager = {
    currentSession: null,
    sessions: [],

    async loadSessions() {
        const result = await API.sessions.list();
        if (result.success) {
            this.sessions = result.data || [];
            this.renderSessionList();
        }
    },

    async createSession(workspaceId, mode = 'STANDARD') {
        if (!workspaceId) {
            alert('请先选择工作区');
            return null;
        }
        const result = await API.sessions.create(workspaceId, mode);
        if (result.success) {
            this.sessions.unshift(result.data);
            this.setCurrentSession(result.data);
            this.renderSessionList();
            return result.data;
        }
        alert(result.error || '创建会话失败');
        return null;
    },

    async destroySession(sessionId) {
        const result = await API.sessions.destroy(sessionId);
        if (result.success) {
            this.sessions = this.sessions.filter(s => s.sessionId !== sessionId);
            if (this.currentSession?.sessionId === sessionId) {
                this.currentSession = this.sessions[0] || null;
            }
            this.renderSessionList();
            if (this.currentSession) {
                Chat.loadHistory(this.currentSession.sessionId);
            } else {
                Chat.showWelcome();
            }
        }
    },

    setCurrentSession(session) {
        this.currentSession = session;
        document.getElementById('currentSession').textContent = 
            session ? `会话 ${session.sessionId.substring(0, 8)}` : '新会话';
        document.getElementById('currentWorkspace').textContent = 
            session?.workspaceId || '工作区';
        this.renderSessionList();
    },

    renderSessionList() {
        const container = document.getElementById('sessionList');
        if (this.sessions.length === 0) {
            container.innerHTML = '<div class="empty-hint">暂无会话</div>';
            return;
        }

        container.innerHTML = this.sessions.map(s => `
            <div class="session-item ${s.sessionId === this.currentSession?.sessionId ? 'active' : ''}" 
                 data-session-id="${s.sessionId}">
                <span class="session-title">${s.sessionId.substring(0, 8)}...</span>
                <button class="session-delete" data-session-id="${s.sessionId}" title="删除">×</button>
            </div>
        `).join('');

        // 绑定事件
        container.querySelectorAll('.session-item').forEach(item => {
            item.addEventListener('click', (e) => {
                if (e.target.classList.contains('session-delete')) return;
                const sessionId = item.dataset.sessionId;
                const session = this.sessions.find(s => s.sessionId === sessionId);
                if (session) {
                    this.setCurrentSession(session);
                    Chat.loadHistory(sessionId);
                }
            });
        });

        container.querySelectorAll('.session-delete').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                if (confirm('确定删除此会话？')) {
                    this.destroySession(btn.dataset.sessionId);
                }
            });
        });
    }
};

/**
 * API通信模块
 * 封装与后端的所有HTTP通信
 */
const API = {
    baseUrl: '',

    async request(method, path, body = null) {
        const options = {
            method,
            headers: { 'Content-Type': 'application/json' }
        };
        if (body) options.body = JSON.stringify(body);

        try {
            const response = await fetch(this.baseUrl + path, options);
            const data = await response.json();
            return data;
        } catch (error) {
            console.error('API请求失败:', error);
            return { success: false, error: error.message };
        }
    },

    // 会话API
    sessions: {
        list: () => API.request('GET', '/api/sessions'),
        create: (workspaceId, mode) => API.request('POST', '/api/sessions', { workspaceId, mode }),
        destroy: (sessionId) => API.request('DELETE', `/api/sessions/${sessionId}`)
    },

    // 工作区API
    workspaces: {
        register: (path) => API.request('POST', '/api/workspaces', { path })
    },

    // 插件API
    plugins: {
        list: () => API.request('GET', '/api/plugins'),
        get: (id) => API.request('GET', `/api/plugins/${id}`)
    },

    // 模型API
    models: {
        // 获取当前适配器的模型列表
        list: () => API.request('GET', '/api/models'),
        // 获取指定适配器的模型列表
        listByAdapter: (adapterType) => API.request('GET', `/api/models/${adapterType}`),
        // 获取模型详情
        detail: (modelId, adapterType) => {
            let url = `/api/models/detail?modelId=${encodeURIComponent(modelId)}`;
            if (adapterType) url += `&adapterType=${adapterType}`;
            return API.request('GET', url);
        },
        // 获取指定模型的思考等级选项
        thinkingLevels: (modelId, adapterType) => {
            let url = `/api/models/thinking-levels?modelId=${encodeURIComponent(modelId)}`;
            if (adapterType) url += `&adapterType=${adapterType}`;
            return API.request('GET', url);
        },
        // 获取本地GGUF模型
        local: () => API.request('GET', '/api/models/local'),
        // 获取云端模型
        cloud: () => API.request('GET', '/api/models/cloud'),
        // 获取提供商模板
        providers: () => API.request('GET', '/api/providers/templates'),
        tokenPlanProviders: () => API.request('GET', '/api/providers/templates/token-plan')
    },

    // 聊天API
    chat: {
        send: (sessionId, message, model, thinkingLevel) => 
            API.request('POST', '/api/chat', { sessionId, message, model, thinkingLevel }),
        switchAdapter: (sessionId, adapterType) => 
            API.request('POST', '/api/chat/adapter/switch', { sessionId, adapterType }),
        adapterStatus: () => API.request('GET', '/api/chat/adapter/status')
    }
};

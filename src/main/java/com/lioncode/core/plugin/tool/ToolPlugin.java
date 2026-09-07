package com.lioncode.core.plugin.tool;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * 工具插件接口
 * 
 * Tool工具作为插件实现，提供具体的操作能力。
 * 例如：文件读写、Shell命令、Git操作等。
 */
public interface ToolPlugin extends Plugin {

    @Override
    default PluginType getType() {
        return PluginType.TOOL;
    }

    /**
     * 获取工具的OpenAI function定义
     * 用于模型function calling
     */
    Map<String, Object> getFunctionDefinition();

    /**
     * 执行工具调用
     * 
     * @param arguments 工具调用参数
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> arguments);

    /**
     * 获取工具类别
     */
    ToolCategory getCategory();

    /**
     * 判断在指定工作模式下是否可用
     */
    boolean isAvailableInMode(AgentMode mode);

    /**
     * 获取需要的权限等级
     */
    PermissionLevel getRequiredPermission();

    /**
     * 工具类别枚举
     */
    enum ToolCategory {
        /** 文件操作 */
        FILE_OPERATION,
        /** 文件修改 */
        FILE_MODIFY,
        /** 文件查找检索 */
        FILE_SEARCH,
        /** 网络搜索 */
        WEB_SEARCH,
        /** Shell命令执行 */
        SHELL,
        /** Git版本控制 */
        GIT,
        /** 其他工具 */
        OTHER
    }

    /**
     * 权限等级枚举
     */
    enum PermissionLevel {
        /** 只读 */
        READ_ONLY,
        /** 工作区写 */
        WORKSPACE_WRITE,
        /** 全部权限 */
        FULL_ACCESS
    }
}

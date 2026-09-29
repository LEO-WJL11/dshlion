package com.lioncode.core.plugin.tool;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * 工具插件抽象基类
 * 
 * 提供通用的工具实现逻辑，减少重复代码。
 * 子类只需实现具体的执行逻辑。
 */
public abstract class AbstractToolPlugin implements ToolPlugin {

    private boolean initialized = false;

    @Override
    public PluginType getType() {
        return PluginType.TOOL;
    }

    @Override
    public void initialize() {
        this.initialized = true;
    }

    @Override
    public void destroy() {
        this.initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public Map<String, Object> getFunctionDefinition() {
        return Map.of(
            "name", getName(),
            "description", getDescription(),
            "parameters", getParametersSchema()
        );
    }

    /**
     * 获取参数JSON Schema（子类实现）
     */
    protected abstract Map<String, Object> getParametersSchema();

    @Override
    public boolean isAvailableInMode(AgentMode mode) {
        // 极简模式下只开放文件和Shell工具
        if (mode == AgentMode.MINIMAL) {
            ToolCategory cat = getCategory();
            return cat == ToolCategory.FILE_OPERATION 
                || cat == ToolCategory.FILE_MODIFY
                || cat == ToolCategory.SHELL;
        }
        // 创造模式下可用工具取决于具体工具
        if (mode == AgentMode.CREATIVE) {
            return true;
        }
        // 标准和PTC模式下所有工具可用
        return true;
    }

    @Override
    public PermissionLevel getRequiredPermission() {
        return PermissionLevel.WORKSPACE_WRITE;
    }

    /**
     * 构建成功结果
     */
    protected ToolResult success(String content) {
        return ToolResult.success(content);
    }

    /**
     * 构建错误结果
     */
    protected ToolResult error(String error) {
        return ToolResult.error(error);
    }

    /**
     * 安全获取字符串参数
     */
    protected String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        return val instanceof String s ? s : defaultValue;
    }

    /**
     * 安全获取必填字符串参数。
     *
     * <p>两条经验（都是实测踩出来的）：
     * <ol>
     *   <li><b>别只收 String</b>：文本通道（本地盒子默认）下所有参数都是字符串，
     *       但云端模型常把数字/布尔当数字给（{@code {"input": 123}}），
     *       以前会误报"缺少必需参数"，模型以为自己给了、于是反复重试。这里统一转字符串。</li>
     *   <li><b>报错要能照着改</b>：只写"缺少必需参数: mode"，模型不知道 mode 该填什么。
     *       实测用户日志里 {@code head_tail_file} 因为漏了 mode 连错 5 次。
     *       所以把本工具**全部必填参数**连同说明一起回给模型，它下一轮基本能一次改对。</li>
     * </ol>
     */
    protected String getRequiredStringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        String text = val == null ? null : String.valueOf(val);
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text.trim())) {
            throw new IllegalArgumentException("缺少必需参数: " + key + requiredParamsHint());
        }
        return text;
    }

    /** 把本工具的必填参数（带说明）列出来，附在报错后面，方便模型下一轮改对。 */
    private String requiredParamsHint() {
        try {
            Object defObj = getFunctionDefinition() == null ? null : getFunctionDefinition().get("parameters");
            if (!(defObj instanceof Map<?, ?> def)) {
                return "";
            }
            Object reqObj = def.get("required");
            java.util.List<String> req = new java.util.ArrayList<>();
            if (reqObj instanceof Object[] arr) {
                for (Object o : arr) {
                    req.add(String.valueOf(o));
                }
            } else if (reqObj instanceof java.util.Collection<?> col) {
                for (Object o : col) {
                    req.add(String.valueOf(o));
                }
            }
            if (req.isEmpty()) {
                return "";
            }
            Object propsObj = def.get("properties");
            StringBuilder sb = new StringBuilder("。本工具必填参数：");
            for (int i = 0; i < req.size(); i++) {
                String name = req.get(i);
                String desc = "";
                if (propsObj instanceof Map<?, ?> props && props.get(name) instanceof Map<?, ?> prop) {
                    Object d = prop.get("description");
                    desc = d == null ? "" : String.valueOf(d);
                }
                sb.append(i == 0 ? "" : "、").append(name).append("（").append(desc).append("）");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析路径参数：相对路径基于当前会话绑定的工作区根目录
     */
    protected String resolvePath(String rawPath) {
        return com.lioncode.core.workspace.WorkspaceContext.resolve(rawPath);
    }

    /**
     * 当前会话绑定工作区路径（未设置时为null）
     */
    protected String currentWorkspace() {
        return com.lioncode.core.workspace.WorkspaceContext.get();
    }
}

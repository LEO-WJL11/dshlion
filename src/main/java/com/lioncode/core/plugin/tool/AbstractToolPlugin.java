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

    /** 是不是 Windows。 */
    protected static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String cachedGit;

    /**
     * 找到 git 可执行文件。
     *
     * <p>为什么不能直接写 "git"：LionBox 是从开始菜单/注册表拉起来的 GUI 程序，
     * 它的 PATH 常常比用户在终端里的短，`new ProcessBuilder("git")` 可能直接找不到；
     * 另外用户也可能是"便携版 Git"。所以按顺序找：GIT_EXE 环境变量 → PATH → 常见安装位置。
     */
    protected static String gitExecutable() {
        if (cachedGit != null) {
            return cachedGit;
        }
        String fromEnv = System.getenv("GIT_EXE");
        if (fromEnv != null && !fromEnv.isBlank() && new java.io.File(fromEnv).isFile()) {
            return cachedGit = fromEnv;
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add("C:\\Program Files\\Git\\cmd\\git.exe");
        candidates.add("C:\\Program Files (x86)\\Git\\cmd\\git.exe");
        candidates.add("C:\\Program Files\\Git\\bin\\git.exe");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            candidates.add(local + "\\Programs\\Git\\cmd\\git.exe");
        }
        for (String c : candidates) {
            if (new java.io.File(c).isFile()) {
                return cachedGit = c;
            }
        }
        return cachedGit = "git";   // 交给 PATH
    }

    /**
     * 跑 git 命令前的目录检查：目录不存在时给出能照着做的错误。
     *
     * <p>原来直接把不存在的目录塞给 ProcessBuilder，用户看到的是
     * {@code Cannot run program "git" (in directory "..."): CreateProcess error=267, 目录名称无效}，
     * 模型据此得出"本机未安装 git"的错误结论（真实原因只是目录没建）。
     */
    protected String checkGitDirectory(String path) {
        java.io.File dir = new java.io.File(path);
        if (!dir.isDirectory()) {
            return "目录不存在: " + path + "（先用 create_directory 建目录，或直接 git_init 建仓库；"
                + "git 命令必须在真实存在的目录里执行）";
        }
        return null;
    }

    /**
     * 把 git 相关异常翻译成"能照着做"的提示。
     *
     * <p>实测（用户日志）：在还没建的目录里跑 git，报的是
     * {@code Cannot run program "git" (in directory "…\.git_test"): CreateProcess error=267, 目录名称无效}，
     * 模型据此得出了"本机未安装 git"的错误结论，后面一连串 git 工具都不敢用了。
     * 真正的原因只是目录没建，所以这里把两种常见情况点明。
     */
    protected String gitHint(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("267") || m.contains("目录名称无效") || m.contains("Invalid directory")
                || m.contains("The directory name is invalid")) {
            return "。这次操作的**目录不存在**（不是没装 git）：先用 create_directory 建目录，"
                + "或者直接用 git_init（它会自动建）";
        }
        if (m.contains("Cannot run program \"git\"") || m.contains("CreateProcess error=2,")
                || m.contains("找不到指定的文件")) {
            return "。没找到 git 可执行文件：装一个 Git for Windows，或用 GIT_EXE 环境变量指定 git.exe 的路径";
        }
        return "";
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
    protected String requiredParamsHint() {
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

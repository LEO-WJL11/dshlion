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
        // 极简模式：只开放"文件类 + shell 类"工具。
        // 文件类 = 文件操作 / 文件修改 / 文件检索（glob_files、search_in_files 也是文件工具），
        // shell 类 = execute_command 与后台进程那一组。其余（网络、Git、编解码…）一律不给，
        // 连工具清单里都不出现 —— 这样模型不会去试，也不会刷出一屏 ❌。
        if (mode == AgentMode.MINIMAL) {
            ToolCategory cat = getCategory();
            return cat == ToolCategory.FILE_OPERATION
                || cat == ToolCategory.FILE_MODIFY
                || cat == ToolCategory.FILE_SEARCH
                || cat == ToolCategory.SHELL;
        }
        // 创造模式（已不再开放给用户）下所有工具可用 ——
        // 现在只剩标准和极简，PTC/CREATIVE 在 AgentMode.normalize() 就被归一成标准了，
        // 这两条留着是为了老会话、老配置不会因为少了个分支而出意外。
        if (mode == AgentMode.CREATIVE) {
            return true;
        }
        // 标准模式（含由 PTC 归一过来的）：所有工具可用
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
     * 安全获取整数参数。
     *
     * <p>【实测教训】文本通道（本地盒子默认）下，模型给的所有参数都是**字符串**：
     * 它写 {@code <parameter=lines>5</parameter>}，工具里却是
     * {@code ((Number) arguments.get("lines")).intValue()} —— 直接
     * {@code ClassCastException: class java.lang.String cannot be cast to class java.lang.Number}，
     * 用户看到的就是"head_tail_file ❌/line 读取失败"。
     * 一个数字参数就废掉一个工具，所以这里统一收口：字符串、数字、布尔、null 全认，
     * 认不出来就用默认值（不抛异常）。
     */
    protected int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(val).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return defaultValue;
        }
        try {
            // 兼容 "5" / "5.0" / "5 行" / "3个" 这类模型写法
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-?\\d+(\\.\\d+)?").matcher(s);
            if (m.find()) {
                return (int) Double.parseDouble(m.group());
            }
        } catch (Exception ignore) {
            // 走默认值
        }
        return defaultValue;
    }

    /**
     * 安全获取布尔参数：同样兼容字符串 "true"/"false"/"1"/"0"/"是"/"否"。
     */
    protected boolean getBoolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(val).trim().toLowerCase();
        return switch (s) {
            case "true", "1", "yes", "y", "是", "真" -> true;
            case "false", "0", "no", "n", "否", "假" -> false;
            default -> defaultValue;
        };
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
     * 这个 git 仓库是不是"空仓库"（初始化过、但还没有任何提交）。
     *
     * <p>判据：`git rev-parse --verify HEAD` 失败 —— 没有提交时 HEAD 指向不存在的 master，
     * 所以 `git branch <名字>` 会报 {@code fatal: not a valid object name: 'master'}。
     * 认识这个状态，工具就能改成做真正有用的事（checkout -b），而不是把 git 的原话甩给模型。
     */
    protected static boolean isEmptyRepository(String dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitExecutable(), "rev-parse", "--verify", "HEAD");
            pb.directory(new java.io.File(dir));
            pb.redirectErrorStream(true);
            gitEnv(pb);
            Process p = pb.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            p.getInputStream().readAllBytes();   // 排空，避免子进程阻塞
            return p.exitValue() != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 给 git 的进程加上"绝不等凭据"的环境。
     *
     * <p>实测教训：`git remote show origin` 会去连远端；远端要凭据时 git 会**交互式等输入**，
     * 而工具里是先 readAllBytes() 再 waitFor()，于是永远阻塞 —— 那条消息卡了 3 分多钟。
     * 设了这几个变量，git 遇到需要凭据就直接失败返回，不会吊住。
     */
    protected static void gitEnv(ProcessBuilder pb) {
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");
        pb.environment().put("SSH_ASKPASS", "echo");
        pb.environment().put("GCM_INTERACTIVE", "never");
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
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
     * 容错读文本文件。
     *
     * <p>【实测】用户机器上的中文文件有的是 ANSI/GBK 存的，原来 {@code Files.readAllLines}
     * 用 UTF-8 严格解码，遇到这种文件直接抛
     * {@code MalformedInputException: Input length = 1}，read_file 报"读取文件失败"。
     * 这里按 UTF-8 → GBK → 替换字符 的顺序退，保证读得出来。
     */
    protected java.util.List<String> readTextLines(java.nio.file.Path path) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        String text = decodeText(bytes);
        // 统一换行后切行（保留末尾空行语义与 readAllLines 一致）
        java.util.List<String> lines = new java.util.ArrayList<>(
            java.util.Arrays.asList(text.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * 认出这个文件原本是什么编码（UTF-8 还是 GBK）。
     *
     * <p>读的时候用 {@link #decodeText} 容错，写回去时必须用**同一个编码** ——
     * 否则记事本存的 ANSI 中文文件被改一次就变成 UTF-8（内容不乱，但编码被悄悄换了，
     * 老工具/批处理可能就读不了）。文件不存在或空文件按 UTF-8。
     */
    protected static java.nio.charset.Charset charsetOf(java.nio.file.Path path) {
        try {
            if (!java.nio.file.Files.exists(path)) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            if (bytes.length == 0) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
            // 能按 UTF-8 严格解出来就当 UTF-8，否则按 GBK（同 decodeText 的判据）
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes));
            return java.nio.charset.StandardCharsets.UTF_8;
        } catch (Exception notUtf8) {
            return java.nio.charset.Charset.forName("GBK");
        }
    }

    /** 按文件原有编码写回（不存在则 UTF-8）。 */
    protected void writeTextPreservingCharset(java.nio.file.Path path, String content)
            throws java.io.IOException {
        java.nio.file.Files.writeString(path, content, charsetOf(path));
    }

    /** 容错读整个文本文件（同上）。 */
    protected String readTextFile(java.nio.file.Path path) throws java.io.IOException {
        return decodeText(java.nio.file.Files.readAllBytes(path));
    }

    /** 按 UTF-8 → GBK 的顺序容错解码（Windows 上命令输出常是 GBK 编码）。 */
    protected static String decodeText(byte[] bytes) {
        // 【注意】`new String(bytes, UTF_8)` **不会抛异常** —— 它把非法字节换成 U+FFFD。
        // 第一版就是拿它当"先试 UTF-8"用的，结果 GBK 文件读出来全是乱码（虽然不报错了）。
        // 必须用 REPORT 模式的解码器：真抛了才说明不是 UTF-8，这时再退 GBK。
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception notUtf8) {
            try {
                return java.nio.charset.Charset.forName("GBK").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (Exception e2) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
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

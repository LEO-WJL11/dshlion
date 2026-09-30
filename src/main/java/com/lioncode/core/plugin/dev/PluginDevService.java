package com.lioncode.core.plugin.dev;

import com.lioncode.core.plugin.PluginKind;
import com.lioncode.core.plugin.PluginPaths;
import com.lioncode.core.plugin.PluginSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件开发模式：开启后，用户可以在软件里直接生成一个**能编译、能加载**的插件工程。
 *
 * <p>三件事：
 * <ol>
 *   <li>开关：启动参数 {@code --lionbox.plugin.dev-mode=true}，或运行时用端点改
 *       （运行时改动会持久化，重启后仍按上次的选择）；</li>
 *   <li>脚手架：{@code plugins/dev/<插件id>/} 下生成源码 + 清单 + 构建脚本 + README；</li>
 *   <li>SDK：把本程序自己的插件接口类导出成一个 {@code lionbox-plugin-api.jar}
 *       —— 没有它，用户根本编译不了（软件是 fat jar 启动的，接口类都在
 *       {@code BOOT-INF/classes} 里，javac 读不进去）。这一步是"能编译"的关键。</li>
 * </ol>
 *
 * <p>为什么默认关闭：这是一个开发者功能。默认开着会在插件目录里凭空多出 dev/ 和 sdk/，
 * 普通用户看到只会困惑"这些是什么、能删吗"。</p>
 */
@Component
public class PluginDevService {

    private static final Logger log = LoggerFactory.getLogger(PluginDevService.class);

    /** SDK jar 里我们要导出的包前缀：插件接口 + 它依赖到的 AgentMode 等都在 com/lioncode 下 */
    private static final String SDK_PREFIX = "com/lioncode/";

    private final PluginPaths paths;
    private final PluginSettings settings;

    /** 启动参数给的值（--lionbox.plugin.dev-mode=true 会被 Spring 收成这个属性） */
    @Value("${lionbox.plugin.dev-mode:false}")
    private boolean devModeByStartup;

    public PluginDevService(PluginPaths paths, PluginSettings settings) {
        this.paths = paths;
        this.settings = settings;
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * 现在是不是开发模式。
     *
     * <p>优先级：运行时设置（settings.json 里的 devMode） &gt; 启动参数。
     * 这样"启动时开了、运行时关掉"能生效，而且关掉之后重启不会又变回开着 ——
     * 用户点过的选择必须比启动参数大，否则他会觉得这个开关"关不掉"。</p>
     */
    public boolean isDevMode() {
        Object override = settings.top("devMode");
        if (override instanceof Boolean b) {
            return b;
        }
        if (override instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return devModeByStartup;
    }

    /** 运行时开关（会持久化） */
    public boolean setDevMode(boolean enabled) {
        settings.putTop("devMode", enabled);
        log.info("插件开发模式已{}", enabled ? "开启" : "关闭");
        return enabled;
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /**
     * 生成插件工程的结果。
     *
     * @param path   工程目录
     * @param files  生成的文件（相对工程目录）
     * @param sdkJar 编译用的 SDK jar（生成失败时为 null，此时 README 里说明了怎么手动指定）
     */
    public record ScaffoldResult(Path path, List<String> files, Path sdkJar) {}

    /**
     * 生成一个最小插件工程。
     *
     * @param id   插件ID（同时作为目录名和插件标识，必须唯一）
     * @param name 显示名
     * @param kind 插件分类（决定生成哪种骨架：工具 / 技能 / 系统插件）
     * @throws IllegalArgumentException 参数不合法
     * @throws IllegalStateException    开发模式没开
     */
    public ScaffoldResult scaffold(String id, String name, String kind) {
        if (!isDevMode()) {
            throw new IllegalStateException(
                "插件开发模式未开启：请用 --lionbox.plugin.dev-mode=true 启动，"
                    + "或先调用 POST /api/plugins/dev-mode {\"enabled\": true}");
        }
        String pluginId = safeId(id);
        PluginKind pluginKind = PluginKind.parse(kind);
        if (pluginKind == null) {
            throw new IllegalArgumentException("未知的插件分类: " + kind + "（可选："
                + java.util.Arrays.stream(PluginKind.values())
                    .map(Enum::name).reduce((a, b) -> a + " / " + b).orElse("") + "）");
        }

        String displayName = (name == null || name.isBlank()) ? pluginId : name.trim();
        String packageName = "lionbox.plugins." + pluginId.replace('.', '_').replace('-', '_')
            .toLowerCase(Locale.ROOT);
        String className = camel(pluginId) + suffixFor(pluginKind);

        Path projectDir = paths.devDir().resolve(pluginId);
        try {
            if (Files.isDirectory(projectDir) && hasAnyFile(projectDir)) {
                // 【绝不覆盖】用户可能已经在那儿改了半天代码。想重来就自己删掉目录。
                throw new IllegalStateException("工程目录已存在且不为空，请先删除或换个 id: " + projectDir);
            }
            Files.createDirectories(projectDir);

            List<String> files = new ArrayList<>();
            Path srcDir = projectDir.resolve("src/main/java")
                .resolve(packageName.replace('.', '/'));
            Files.createDirectories(srcDir);

            String javaFile = className + ".java";
            write(srcDir.resolve(javaFile), renderSource(packageName, className, displayName, pluginId, pluginKind));
            files.add("src/main/java/" + packageName.replace('.', '/') + "/" + javaFile);

            Path resourceDir = projectDir.resolve("src/main/resources/META-INF");
            Files.createDirectories(resourceDir);
            write(resourceDir.resolve("lionbox-plugin.properties"),
                "# LionBox 插件清单：告诉加载器哪个类是这个插件\n"
                    + "# 改类名/包名时这里必须一起改，否则插件不会被加载（列表里什么都看不到）\n"
                    + "plugin.class=" + packageName + "." + className + "\n");
            files.add("src/main/resources/META-INF/lionbox-plugin.properties");

            Path sdkJar = tryEnsureSdkJar();
            writeScript(projectDir.resolve("build.ps1"), buildScript(pluginId, sdkJar));
            files.add("build.ps1");

            write(projectDir.resolve("README.md"),
                readme(pluginId, displayName, pluginKind, packageName, className, sdkJar, projectDir));
            files.add("README.md");

            log.info("插件工程已生成: {}（{}）", projectDir, pluginKind);
            return new ScaffoldResult(projectDir, files, sdkJar);
        } catch (IOException e) {
            throw new IllegalStateException("生成插件工程失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验插件ID并当做目录名用。
     *
     * <p>【安全】id 直接变成目录名，所以必须在这里挡住 {@code ..} 和路径分隔符 ——
     * 否则一个 {@code ../../} 就能把文件写到插件目录外面去。
     * 只允许字母、数字、点、下划线、短横线，长度 2..64。</p>
     */
    private static String safeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("插件ID不能为空");
        }
        String v = id.trim();
        if (!v.matches("[A-Za-z0-9._-]{2,64}")) {
            throw new IllegalArgumentException("插件ID只能包含字母、数字、点、下划线、短横线，长度 2-64："
                + id);
        }
        if (v.contains("..")) {
            throw new IllegalArgumentException("插件ID不能包含 .. : " + id);
        }
        return v;
    }

    private static String suffixFor(PluginKind kind) {
        return switch (kind) {
            case SKILL -> "Skill";
            case BASE_TOOL, ADVANCED_TOOL -> "Tool";
            default -> "Plugin";
        };
    }

    /** 目录里有没有东西（用于"绝不覆盖用户已有工程"的判定） */
    private static boolean hasAnyFile(Path dir) {
        try (var list = Files.list(dir)) {
            return list.findAny().isPresent();
        } catch (IOException e) {
            // 读不了就当有东西：宁可拒绝生成，也不能把用户的东西盖了
            return true;
        }
    }

    /** demo.echo → DemoEcho（给类名用） */
    private static String camel(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[._-]+")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
              .append(part.length() > 1 ? part.substring(1) : "");
        }
        return sb.length() == 0 ? "My" : sb.toString();
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * 写 PowerShell 脚本：**必须带 UTF-8 BOM**。
     *
     * <p>【实测踩过的坑】Windows PowerShell 5.1 读没有 BOM 的 .ps1 时按系统 ANSI 代码页解码
     * （中文机器 = GBK）。我们生成的脚本里有中文注释和中文提示，被当成 GBK 解出来就是一堆乱码，
     * 而乱码里的 {@code ?} {@code <} 会直接让解析器报
     * {@code Unexpected token ')' in expression or statement} ——
     * 用户拿到一个"生成得很好、就是跑不起来"的脚本，还完全看不出是编码问题。
     * 加了 BOM，PS 5.1 和 PS 7 都会按 UTF-8 读。</p>
     */
    private void writeScript(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(file, all);
    }

    // ------------------------------------------------------------------
    // 源码模板
    // ------------------------------------------------------------------

    private String renderSource(String pkg, String cls, String displayName, String pluginId,
                                PluginKind kind) {
        return switch (kind) {
            case SKILL -> skillTemplate(pkg, cls, displayName, pluginId);
            case BASE_TOOL, ADVANCED_TOOL -> toolTemplate(pkg, cls, displayName, pluginId, kind);
            default -> systemTemplate(pkg, cls, displayName, pluginId, kind);
        };
    }

    /** 工具插件骨架（基础工具 / 进阶工具） */
    private String toolTemplate(String pkg, String cls, String displayName, String pluginId,
                                PluginKind kind) {
        boolean minimal = kind == PluginKind.BASE_TOOL;
        return """
            package %s;

            import com.lioncode.core.agent.AgentMode;
            import com.lioncode.core.plugin.tool.AbstractToolPlugin;
            import com.lioncode.core.plugin.tool.ToolResult;

            import java.util.Map;

            /**
             * %s（LionBox 插件开发模式生成的示例工具插件）。
             *
             * <p>它已经是一个**能编译、能加载**的完整插件：编译出 jar 放进插件目录、
             * 调一次 /api/plugins/reload，模型就能看到并使用 %s 这个工具了。</p>
             */
            public class %s extends AbstractToolPlugin {

                /** 插件唯一ID：设置里的开关、日志、卸载都认它。**必须全库唯一**，重了会被跳过。 */
                @Override
                public String getId() { return "%s"; }

                /** 工具名：模型看到并调用的名字（英文、下划线，别和内置工具重名）。 */
                @Override
                public String getName() { return "%s"; }

                /** 界面上显示的名字（中文更好读；不写就退回工具名）。 */
                @Override
                public String getDisplayName() { return "%s"; }

                @Override
                public String getDescription() {
                    return "示例插件：把输入的文本原样返回，用来验证插件能被加载和执行";
                }

                @Override
                public ToolCategory getCategory() { return ToolCategory.OTHER; }

                /**
                 * 在哪些模式下可用。
                 *
                 * <p>【这就是"基础工具/进阶工具"的判定依据】插件分类是从这个方法**派生**的：
                 * 极简模式（MINIMAL）也能用 → 基础工具；只有标准模式能用 → 进阶工具。
                 * 所以不需要（也没有）一个"我属于哪一类"的字段要你填。</p>
                 */
                @Override
                public boolean isAvailableInMode(AgentMode mode) {
                    %s
                }

                /** 参数 schema：写清类型和说明，模型才给得对。 */
                @Override
                protected Map<String, Object> getParametersSchema() {
                    return Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "text", Map.of("type", "string", "description", "要回显的文本")
                        ),
                        "required", new String[]{"text"}
                    );
                }

                /**
                 * 真正干活的地方。
                 *
                 * <p>约定：成功用 success(...)，失败用 error(...)（error 的内容会原样回给模型，
                 * 所以要写成"能照着改"的话，别只写"失败了"）。异常一定要自己接住 ——
                 * 插件抛出去的异常会变成模型看到的一整轮报错。</p>
                 */
                @Override
                public ToolResult execute(Map<String, Object> arguments) {
                    try {
                        String text = getRequiredStringArg(arguments, "text");
                        return success("插件收到: " + text);
                    } catch (Exception e) {
                        return error("示例插件执行失败: " + e.getMessage());
                    }
                }
            }
            """.formatted(pkg, displayName, displayName, cls, pluginId, pluginId, displayName,
            minimal
                ? "// 极简模式也放行 —— 不重写这个方法的话，AbstractToolPlugin 只放行文件类和 shell 类\n"
                    + "        // （我们的类别是 OTHER），极简模式里就看不见这个工具了。\n"
                    + "        return true;"
                : "// 只在标准模式可用：极简模式是给「少而精」的场景用的，别往里加东西。\n"
                    + "        return mode != AgentMode.MINIMAL;");
    }

    /** 技能插件骨架 */
    private String skillTemplate(String pkg, String cls, String displayName, String pluginId) {
        return """
            package %s;

            import com.lioncode.core.plugin.skill.SkillPlugin;

            import java.util.List;

            /**
             * %s（LionBox 插件开发模式生成的示例技能插件）。
             *
             * <p>技能和工具的区别：工具是"模型能做的事"，技能是"模型该知道的规矩"。
             * 技能把一段领域经验注入系统提示词，让模型在这个领域里少走弯路。</p>
             */
            public class %s implements SkillPlugin {

                @Override
                public String getId() { return "%s"; }

                @Override
                public String getName() { return "%s"; }

                @Override
                public String getDisplayName() { return "%s"; }

                @Override
                public String getDescription() {
                    return "示例技能：往系统提示词里注入一段领域提示";
                }

                /**
                 * 注入系统提示词的片段。
                 *
                 * <p>【写短】这段文字每一轮请求都会带上，写几百字等于每轮都多花几百 token。
                 * 只写"模型不知道、又必须知道"的规矩，通用常识别写。</p>
                 */
                @Override
                public String getSystemPromptFragment() {
                    return "## 示例技能\\n回答里必须提到「插件开发模式」这个词。";
                }

                /** 这个技能依赖哪些工具（只做展示，工具开关仍然各自独立）。 */
                @Override
                public List<String> getRequiredToolIds() { return List.of(); }

                /** 适用任务类型的描述（界面上展示用）。 */
                @Override
                public List<String> getTaskTypeDescriptions() {
                    return List.of("演示技能插件怎么工作");
                }
            }
            """.formatted(pkg, displayName, cls, pluginId, pluginId, displayName);
    }

    /** 其他系统插件骨架（终端/大循环/子智能体/团队/审查/自动化） */
    private String systemTemplate(String pkg, String cls, String displayName, String pluginId,
                                  PluginKind kind) {
        return """
            package %s;

            import com.lioncode.core.plugin.Plugin;
            import com.lioncode.core.plugin.PluginKind;

            /**
             * %s（LionBox 插件开发模式生成的系统插件骨架）。
             *
             * <p>系统插件不提供工具，它"改变别的插件怎么跑"。如果要插进 Agent 主循环，
             * 让它同时实现 {@code com.lioncode.core.agent.spi.AgentSpi}：
             * 加载器发现它实现了 AgentSpi 会自动注册，卸载时会自动摘掉。</p>
             */
            public class %s implements Plugin {

                @Override
                public String getId() { return "%s"; }

                @Override
                public String getName() { return "%s"; }

                @Override
                public String getDisplayName() { return "%s"; }

                @Override
                public String getDescription() { return "示例系统插件（%s）"; }

                /** 系统插件：既不是工具也不是技能。 */
                @Override
                public PluginType getType() { return PluginType.SYSTEM; }

                @Override
                public PluginKind getKind() { return PluginKind.%s; }

                /**
                 * 默认开不开。
                 *
                 * <p>会主动干活、或会明显改变体感的插件（自动化、审查）应当返回 false，
                 * 让用户主动开 —— 升级完就悄悄改变行为是最招人烦的。</p>
                 */
                @Override
                public boolean isEnabledByDefault() { return false; }
            }
            """.formatted(pkg, displayName, cls, pluginId, pluginId, displayName,
            kind.getDisplayName(), kind.name());
    }

    // ------------------------------------------------------------------
    // build.ps1 / README
    // ------------------------------------------------------------------

    private String buildScript(String pluginId, Path sdkJar) {
        // PowerShell 里不用反斜杠：Java 文本块里反斜杠是转义符，写成单个反斜杠会编译不过，
        // 而 Join-Path 用正斜杠在 Windows 上照样工作。
        String sdkDefault = sdkJar == null ? "" : sdkJar.toAbsolutePath().toString().replace('\\', '/');
        return """
            # LionBox 插件构建脚本（由插件开发模式生成）
            #
            # 做的事：用 JDK 的 javac 编译 src/main/java，打成 jar 放进插件目录，然后你去
            # 调一次 POST /api/plugins/reload（或重启软件）就能用上。
            #
            # 用法：  powershell -ExecutionPolicy Bypass -File build.ps1
            # 需要：  JDK 17+（javac）。没装 JDK 的话先去装一个，或把 -Javac 指到已有的 javac。
            param(
                [string]$Sdk = "%s",
                [string]$Javac = ""
            )

            # 控制台按 UTF-8 输出，中文提示才不会变成乱码（这个文件本身也带了 UTF-8 BOM）
            try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

            $ErrorActionPreference = 'Stop'
            $here = Split-Path -Parent $MyInvocation.MyCommand.Path
            # 【别写成 Join-Path $here '..' '..'】那是 PowerShell 6+ 才有的多段写法，
            # Windows 自带的 PowerShell 5.1 只收两个位置参数，会直接报
            # "A positional parameter cannot be found that accepts argument '..'"。
            # 嵌套两次 Join-Path（每次两个参数）在 5.1 和 7 上都能跑。
            $pluginsDir = (Resolve-Path (Join-Path (Join-Path $here '..') '..')).Path
            $outJar = Join-Path $pluginsDir '%s.jar'
            $classes = Join-Path $here 'build/classes'

            if (-not $Javac) {
                if ($env:JAVA_HOME) { $Javac = Join-Path $env:JAVA_HOME 'bin/javac.exe' }
                if (-not (Test-Path $Javac)) { $Javac = (Get-Command javac -ErrorAction SilentlyContinue).Source }
            }
            if (-not $Javac -or -not (Test-Path $Javac)) {
                throw "找不到 javac。请安装 JDK 17+，或用 -Javac <路径> 指定。"
            }
            if (-not $Sdk -or -not (Test-Path $Sdk)) {
                throw "找不到插件 SDK（$Sdk）。它是编译插件时用的接口包；在软件里重新生成一次工程，或用 -Sdk <路径> 指定 lionbox-plugin-api.jar。"
            }

            if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
            New-Item -ItemType Directory -Path $classes -Force | Out-Null

            $sources = Get-ChildItem -Path (Join-Path $here 'src/main/java') -Recurse -Filter *.java |
                ForEach-Object { $_.FullName }
            Write-Host "[build] 编译 $($sources.Count) 个源文件…"
            & $Javac -encoding UTF-8 -nowarn -cp $Sdk -d $classes $sources
            if ($LASTEXITCODE -ne 0) { throw "javac 编译失败（exit=$LASTEXITCODE）" }

            # 清单文件（META-INF/lionbox-plugin.properties）必须一起打进 jar，
            # 否则加载器不知道哪个类是插件。
            $res = Join-Path $here 'src/main/resources'
            if (Test-Path $res) { Copy-Item (Join-Path $res '*') $classes -Recurse -Force }

            $jarExe = Join-Path (Split-Path -Parent $Javac) 'jar.exe'
            & $jarExe --create --file $outJar -C $classes .
            if ($LASTEXITCODE -ne 0) { throw "打包失败（exit=$LASTEXITCODE）" }

            Write-Host "[build] 完成: $outJar"
            Write-Host "[build] 下一步：调用 POST /api/plugins/reload（或重启 LionBox）让插件生效。"
            """.formatted(sdkDefault, pluginId);
    }

    private String readme(String pluginId, String displayName, PluginKind kind, String pkg,
                          String cls, Path sdkJar, Path projectDir) {
        String sdkLine = sdkJar == null
            ? "SDK 这次没生成成功（可能是打包方式特殊）。可以手动指定：`-Sdk <lionbox-plugin-api.jar 路径>`；"
                + "如果实在拿不到，把本程序 `com/lioncode` 下的 class 打包成一个 jar 也行。"
            : "SDK 已经生成好了：`" + sdkJar.toAbsolutePath() + "`（由本程序导出，别删）。";
        return """
            # %s（插件 id: `%s`）

            这是 LionBox **插件开发模式**生成的最小插件工程，分类是「%s」。
            它现在就能编译、能加载 —— 你可以先跑通一遍，再改成自己需要的样子。

            ## 目录结构

            ```
            %s/
              src/main/java/%s/%s.java    ← 插件本体（改这里）
              src/main/resources/META-INF/lionbox-plugin.properties  ← 声明哪个类是插件
              build.ps1                   ← 一键编译 + 打包 + 放进插件目录
              README.md                   ← 你正在看的这个
            ```

            ## 编译 & 加载（三步）

            1. 编译打包：`powershell -ExecutionPolicy Bypass -File build.ps1`
               （需要 JDK 17+；脚本会用 javac 编译并生成 `../../%s.jar`）
            2. 让软件重新扫描插件：`POST /api/plugins/reload`，或直接重启 LionBox
            3. 到「设置 → 插件」里确认它出现了、开关是开的；然后让模型调用它试试

            %s

            ## 要改的地方

            - **工具名**（`getName()`）：模型就是按这个名字调用的。改成你想让模型做的事，
              例如 `check_license_header`。别和内置工具重名，重名会让内置那个失效。
            - **说明**（`getDescription()`）：写在工具清单里给模型看，写清"什么时候该用它"。
            - **参数**（`getParametersSchema()`）：JSON Schema，`required` 里的参数模型必须给。
            - **逻辑**（`execute()`）：真正干活的地方。记住两条约定：
              成功用 `success(...)`，失败用 `error(...)`；错误信息要写成"能照着改"的话。
            - **插件ID**（`getId()`）：设置里的开关、日志、卸载都认它，**必须全库唯一**，
              和别的插件重了会被跳过（列表里会出现一条"ID冲突"的错误）。

            ## 热插拔是怎么工作的（以及什么时候会不灵）

            - 加载：软件扫描插件目录下的 `*.jar`，每个 jar 用**独立的 ClassLoader** 加载，
              所以你改完重新 build 再 `reload` 就能生效，不用重启软件。
            - 卸载：`DELETE /api/plugins/{id}` 会把插件从注册表里摘掉、关掉它的 ClassLoader。
            - **注意**：如果你在 `initialize()` 里起了线程、开了端口、注册了全局监听，
              一定要在 `destroy()` 里关掉 —— ClassLoader 关掉不会自动帮你收拾这些。
            - 插件之间**不要**互相 `new` 对方的类：每个插件是独立的 ClassLoader，
              跨插件强转会得到 `ClassCastException`。要通信就用返回给模型的结果、或事件总线。

            ## 能插进主循环吗？

            能。让插件类**同时实现** `com.lioncode.core.agent.spi.AgentSpi`，加载器会自动注册它；
            卸载时自动摘掉。可用的扩展点（都只要实现你关心的那一个）：

            | 方法 | 作用 |
            |---|---|
            | `filterToolNames` | 决定这一轮下发给模型的工具清单 |
            | `extraSystemSections` | 往系统提示词追加段落 |
            | `transformUserMessage` | 改写信用户消息（例如展开 @ 引用） |
            | `loopOptions` | 覆盖主循环参数（最大轮次/工具超时/空转容忍） |

            > 提示：`extraSystemSections` 里塞的东西每一轮都会进提示词，写短一点。
            """.formatted(displayName, pluginId, kind.getDisplayName(),
            projectDir.getFileName().toString(), pkg.replace('.', '/'), cls, pluginId, sdkLine);
    }

    // ------------------------------------------------------------------
    // SDK（编译插件用的接口包）
    // ------------------------------------------------------------------

    /**
     * 生成（或复用）插件 SDK jar。
     *
     * <p>【为什么必须做这一步】用户要编译插件就得能 `import com.lioncode.core.plugin.Plugin`。
     * 但软件是 fat jar 跑的，接口类在 {@code BOOT-INF/classes/} 里面 ——
     * `javac -cp lion-code-agent-harness.jar` 是**找不到**它们的（javac 不会钻进 fat jar 的
     * 嵌套目录）。所以这里把 `com/lioncode/**` 抽出来，重新打成一个普通的 jar。
     * 开发态（IDE/`target/classes`）就直接用那个目录，不用抽。</p>
     *
     * <p>失败返回 null：生成不了 SDK 不该让整个脚手架失败 ——
     * 源码本身仍然是有价值的，README 里也写清了怎么手动补。</p>
     */
    public Path ensureSdkJar() {
        try {
            return ensureSdkJarInternal();
        } catch (Exception e) {
            log.warn("生成插件 SDK 失败（脚手架继续，用户可手动指定）: {}", e.toString());
            return null;
        }
    }

    private Path tryEnsureSdkJar() {
        return ensureSdkJar();
    }

    private synchronized Path ensureSdkJarInternal() throws Exception {
        Path sdkDir = paths.pluginsDir().resolve("sdk");
        Path jar = sdkDir.resolve("lionbox-plugin-api.jar");
        Path stamp = sdkDir.resolve("build-stamp.txt");

        Path codeSource = ownCodeSource();
        if (codeSource == null) {
            return null;
        }
        String stampValue = codeSource.toAbsolutePath() + "|" + Files.getLastModifiedTime(codeSource).toMillis()
            + "|" + (Files.isRegularFile(codeSource) ? Files.size(codeSource) : 0);
        if (Files.isRegularFile(jar) && Files.isRegularFile(stamp)
                && stampValue.equals(Files.readString(stamp).trim())) {
            return jar;   // 同一份程序，不用重复抽
        }

        Files.createDirectories(sdkDir);
        Path tmp = sdkDir.resolve("lionbox-plugin-api.jar.tmp");
        int count;
        if (Files.isDirectory(codeSource)) {
            count = zipFromDirectory(codeSource, tmp);
        } else {
            count = zipFromFatJar(codeSource, tmp);
        }
        if (count == 0) {
            Files.deleteIfExists(tmp);
            log.warn("插件 SDK 里一个类都没抽到（代码来源: {}）", codeSource);
            return null;
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(stamp, stampValue);
        log.info("插件 SDK 已生成: {}（{} 个 class，来源 {}）", jar, count, codeSource.getFileName());
        return jar;
    }

    /** 本程序自己的 class 来源：目录（开发态）或 jar（打包后） */
    private Path ownCodeSource() {
        try {
            URI uri = com.lioncode.core.plugin.Plugin.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            return Path.of(uri);
        } catch (Exception e) {
            log.warn("拿不到本程序的 class 来源: {}", e.toString());
            return null;
        }
    }

    /** 开发态：直接从 target/classes 这类目录里挑 com/lioncode/** 重新打包 */
    private int zipFromDirectory(Path root, Path out) throws IOException {
        List<Path> classes = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    return rel.startsWith(SDK_PREFIX) && rel.endsWith(".class");
                })
                .forEach(classes::add);
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            for (Path c : classes) {
                String rel = root.relativize(c).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(rel));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
        }
        return classes.size();
    }

    /** 打包态：从 fat jar 的 BOOT-INF/classes 里抽 com/lioncode/** */
    private int zipFromFatJar(Path fatJar, Path out) throws IOException {
        int count = 0;
        try (JarFile jarFile = new JarFile(fatJar.toFile());
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                String rel = name.startsWith("BOOT-INF/classes/")
                    ? name.substring("BOOT-INF/classes/".length())
                    : name;
                if (!rel.startsWith(SDK_PREFIX) || !rel.endsWith(".class")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(rel));
                try (InputStream in = jarFile.getInputStream(entry)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
                count++;
            }
        }
        return count;
    }
}

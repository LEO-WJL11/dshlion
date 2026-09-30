package com.lioncode.core.plugin;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 终端插件：常驻终端的行为约束。
 *
 * <p>它本身不提供工具（{@code execute_command} 那三个工具仍然是各自的 {@code ToolPlugin}），
 * 它管的是**这组工具怎么跑**：
 * <ul>
 *   <li>{@code maxCommandSeconds} —— 一条命令最多跑多久（超时由 {@code PersistentShell}
 *       连终端一起杀掉重建，跟老行为一致）；</li>
 *   <li>{@code maxOutputBytes} —— 一条命令最多带回多少字节输出，超出就截断并明确告诉模型。</li>
 * </ul>
 *
 * <p>【为什么要单独做成一个插件】"限制每条命令最长跑多久、最多输出多少"是用户在设置里的诉求，
 * 而这两个值天然属于"终端"这个概念，不属于某一个工具类。放进插件系统后它就有了开关、
 * 有了展示位、有了统一的持久化 —— 而不是散落在某个工具类的静态字段里。</p>
 *
 * <p>关掉它会连带关掉它管的三个工具（见 {@link #OWNED_TOOL_NAMES} 与 {@link PluginGateSpi}）：
 * 关掉终端插件之后还留着 {@code execute_command} 能跑，那不叫关掉。</p>
 */
@Component
public class TerminalPlugin implements Plugin {

    /** 插件ID（设置文件里存的就是它） */
    public static final String PLUGIN_ID = "plugin.terminal";

    /**
     * 这个插件"管着"的工具名。
     *
     * <p>关掉插件 = 这几个工具从下发给模型的清单里一起消失。
     * 写成常量给 {@link PluginGateSpi} 用：门禁在 core/plugin 下，
     * 不该去反向依赖 tool.shell 包里的具体工具类。
     */
    public static final Set<String> OWNED_TOOL_NAMES =
        Set.of("execute_command", "run_background", "stop_background");

    private final PluginSettings settings;

    public TerminalPlugin(PluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "terminal";
    }

    @Override
    public String getDisplayName() {
        return "终端";
    }

    @Override
    public String getDescription() {
        return "常驻终端：限制每条命令最长运行时间、最多输出多少字节（超出截断并告知模型）";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.TERMINAL;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    // ------------------------------------------------------------------
    // 限制值：每次都从设置里现读
    // ------------------------------------------------------------------

    /**
     * 一条命令最多跑多少秒。
     *
     * <p>【为什么不做成字段缓存】用户在设置面板把 300 改成 30，必须**下一条命令**就生效。
     * 缓存成字段的话得重启才生效 —— 而"改了没用"是用户最不能接受的一种 bug。
     * 读的是内存里的 Map，没有 IO，随便读。
     */
    public int maxCommandSeconds() {
        int v = settings.intOf("terminal", "maxCommandSeconds",
            PluginSettings.DEFAULT_MAX_COMMAND_SECONDS);
        // 0 或负数没有意义（命令总要有个上限），一律回落到默认值
        return v > 0 ? v : PluginSettings.DEFAULT_MAX_COMMAND_SECONDS;
    }

    /**
     * 一条命令最多带回多少字节。0 = 不限制（老行为）。
     */
    public int maxOutputBytes() {
        int v = settings.intOf("terminal", "maxOutputBytes",
            PluginSettings.DEFAULT_MAX_OUTPUT_BYTES);
        return Math.max(v, 0);
    }

    /**
     * 把工具请求的超时和插件上限取小值。
     *
     * <p>取小不取大：模型自己写的 {@code timeout} 只是它的"期望"，
     * 插件的上限是用户的"规定"。规定优先，否则模型写个 99999 就把限制绕过去了。
     */
    public int clampTimeout(Integer requestedSeconds) {
        int limit = maxCommandSeconds();
        if (requestedSeconds == null || requestedSeconds <= 0) {
            return limit;
        }
        return Math.min(requestedSeconds, limit);
    }
}

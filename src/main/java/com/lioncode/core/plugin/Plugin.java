package com.lioncode.core.plugin;

import java.util.List;
import java.util.Map;

/**
 * 插件统一接口
 * 
 * 所有插件（Skill技能包、Tool工具）都实现此接口。
 * 核心runtime通过此接口管理插件的生命周期。
 * 
 * 生命周期：创建 -> initialize() -> 使用中 -> destroy()
 */
public interface Plugin {

    /**
     * 获取插件唯一标识
     */
    String getId();

    /**
     * 获取插件名称
     */
    String getName();

    /**
     * 获取插件描述
     */
    String getDescription();

    /**
     * 获取插件类型
     */
    PluginType getType();

    /**
     * 获取插件版本
     */
    default String getVersion() {
        return "1.0.0";
    }

    // ==================================================================
    // 「一切皆插件」新增的一组默认方法。
    //
    // 【为什么全部给 default 实现】现有 60 个工具类 + 4 个技能类一行都不改也必须能编译，
    // 而且第三方 jar 里那些按老接口写的插件也不能因为升级就崩。
    // 所以这里只"补信息"，不改契约：不实现 = 走默认，实现了 = 按你说的来。
    // ==================================================================

    /**
     * 插件分类（用于设置面板分组、按类开关）。
     *
     * <p>默认实现是**派生**出来的，这正是"不用改 60 个工具类"的关键：
     * 工具按「极简模式能不能用」自动分成基础工具/进阶工具，
     * 技能类归 SKILL；只有终端、大循环、团队这些"系统插件"才需要显式覆盖。</p>
     */
    default PluginKind getKind() {
        if (getType() == PluginType.SKILL) {
            return PluginKind.SKILL;
        }
        if (this instanceof com.lioncode.core.plugin.tool.ToolPlugin tool) {
            // 极简模式（MINIMAL）能用的 = 基础工具；其余 = 进阶工具。
            // 判据直接复用工具自己的 isAvailableInMode，避免两处规则各写一份、日后走偏。
            return tool.isAvailableInMode(com.lioncode.core.agent.AgentMode.MINIMAL)
                ? PluginKind.BASE_TOOL
                : PluginKind.ADVANCED_TOOL;
        }
        // 既不是工具也不是技能（外置插件里少见的写法）：当成进阶工具，至少在列表里看得见
        return PluginKind.ADVANCED_TOOL;
    }

    /**
     * 界面上显示的名字。
     *
     * <p>默认取 {@link #getName()}（工具类给的是给模型看的英文名，如 {@code read_file}）——
     * 这样老插件不改也不会空着；系统插件会覆盖成中文名。
     */
    default String getDisplayName() {
        String name = getName();
        return name == null || name.isBlank() ? getId() : name;
    }

    /**
     * 出厂默认是否开启。
     *
     * <p>默认 true = 现有能力一个都不少（这是升级不炸的前提）。
     * 真正"默认关"的只有那些会主动干活的插件（自动化任务、自动审查），
     * 它们没配好就跑反而吓人。
     */
    default boolean isEnabledByDefault() {
        return true;
    }

    /**
     * 卸载后不重启能不能再装回来。
     *
     * <p>内置插件是 Spring 容器里的单例 bean，卸了没法凭空造一个新的，所以默认 false；
     * 外置 jar 由 {@link PluginLoader} 用独立 ClassLoader 装着，能关掉能重扫，所以是 true。
     * 这个字段只影响界面提示（"要不要显示重载按钮"），不影响 unload 本身能不能执行。
     */
    default boolean isHotReloadable() {
        return false;
    }

    /**
     * 插件来源：{@code builtin}（随软件自带）/ {@code external}（用户放进插件目录的 jar）。
     */
    default String getSource() {
        return "builtin";
    }

    /**
     * 获取插件作者
     */
    default String getAuthor() {
        return "Lion-Code";
    }

    /**
     * 获取插件标签（用于分类和搜索）
     */
    default List<String> getTags() {
        return List.of();
    }

    /**
     * 获取插件元数据
     */
    default Map<String, Object> getMetadata() {
        return Map.of();
    }

    /**
     * 初始化插件
     * 在插件注册时调用
     */
    default void initialize() {
        // 默认空实现
    }

    /**
     * 销毁插件
     * 在插件注销时调用
     */
    default void destroy() {
        // 默认空实现
    }

    /**
     * 插件是否已初始化
     */
    default boolean isInitialized() {
        return true;
    }

    /**
     * 获取插件健康状态
     */
    default HealthStatus getHealthStatus() {
        return HealthStatus.HEALTHY;
    }

    /**
     * 健康状态枚举
     */
    enum HealthStatus {
        HEALTHY("健康"),
        DEGRADED("降级"),
        UNHEALTHY("不健康");

        private final String displayName;
        HealthStatus(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /**
     * 插件类型枚举
     */
    enum PluginType {
        /** 技能插件（能力包） */
        SKILL("技能"),
        /** 工具插件 */
        TOOL("工具"),
        /**
         * 系统插件：既不是工具也不是技能，而是"改变别的插件怎么跑"的那一类
         * （终端限制、大循环参数、子智能体、团队、审批审查、自动化任务）。
         *
         * <p>为什么要加这一档：这六类如果硬塞进 TOOL/SKILL，统计里会多出一堆假工具、
         * 假技能（"终端插件"凭什么算技能？），设置面板的分组也跟着乱。
         * 加一个值不动任何既有代码 —— 现有插件没人写死遍历全部类型。</p>
         */
        SYSTEM("系统");

        private final String displayName;
        PluginType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }
}

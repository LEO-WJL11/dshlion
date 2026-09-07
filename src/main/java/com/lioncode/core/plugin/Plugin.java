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
        TOOL("工具");

        private final String displayName;
        PluginType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }
}

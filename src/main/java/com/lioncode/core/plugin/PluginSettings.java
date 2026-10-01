package com.lioncode.core.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件设置（用户开关 + 各类插件参数）的持久化。
 *
 * <p>【为什么必须有这个类】"每个插件都能在设置里开关"这句话，真正的难点不是开关按钮，
 * 而是"关掉之后重启还在"。所以这里不做任何花活：改了立刻落盘，启动时读回来，
 * 每次读取都从内存快照走（不碰磁盘），保证 AgentLoop 每轮问它"这个插件开了吗"也不会有 IO 开销。</p>
 *
 * <p>存储位置：{@code <插件目录>/settings.json}（定位规则见 {@link PluginPaths}）。</p>
 *
 * <p><b>只存"和默认值不一样"的开关</b>：没动过的插件不在文件里出现，
 * 这样以后改代码里的默认值（比如把某个工具的 isEnabledByDefault 改成 false）能自然生效，
 * 而不是被一份陈年老配置按在原地。用户明确点过开关的才记下来。</p>
 */
@Component
public class PluginSettings {

    private static final Logger log = LoggerFactory.getLogger(PluginSettings.class);
    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final PluginPaths paths;

    /** 全部设置：顶层键 -> 值（值通常是 Map，team/automation 里是 List） */
    private final Map<String, Object> data = new ConcurrentHashMap<>();

    public PluginSettings(PluginPaths paths) {
        this.paths = paths;
    }

    // ------------------------------------------------------------------
    // 终端插件默认值 —— 这里是"现在实际行为"的写照，改它等于改所有老用户的行为
    // ------------------------------------------------------------------

    /**
     * 每条命令最长运行秒数。
     *
     * <p>默认 300：读代码确认过 —— {@code ShellExecuteTool} 在模型没给 timeout 时传 300，
     * {@code PersistentShell.run} 对非正数也回落 300。所以 300 就是"保持现在的行为"。
     */
    public static final int DEFAULT_MAX_COMMAND_SECONDS = 300;

    /**
     * 一条命令最多带回多少字节输出。
     *
     * <p>默认 200000（≈200KB）：老版本是**完全不限**，但那意味着一条 {@code type huge.log}
     * 就能把整个上下文窗口冲掉（本地模型 8k 上下文，200KB 文本 = 十几万 token，
     * 结果就是这一轮之后模型彻底失忆）。200KB 对正常命令等于没限制，只在明显跑飞时才截断。
     * 想要老行为就把这个值设成 0（0 = 不限制）。
     */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 200_000;

    /** 大循环：一条消息最多允许几轮工具调用（与 AgentLoop 里原来的兜底值一致） */
    public static final int DEFAULT_MAX_ITERATIONS = 200;
    /** 大循环：单个工具最多跑多少秒（与 lionbox.agent.tool-timeout-seconds 默认值一致） */
    public static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 600;
    /** 大循环：连续几轮"模型什么都没吐"就按正常收尾（与 AgentLoop 的 MAX_CALL_REPAIR 一致） */
    public static final int DEFAULT_SILENT_ROUNDS = 2;
    /**
     * 大循环：一轮最多执行几个工具调用。
     * 0 = 不限，出厂就是这个值 —— 用户明确要求过"模型给几个就执行几个"
     * （本机 11 token/s，砍成一轮一个等于把 50 个工具拖成 7 分钟）。
     */
    public static final int DEFAULT_MAX_TOOLS_PER_ROUND = 0;
    /** 子智能体：默认只允许往下派 1 层（主 Agent → 子智能体），子智能体不能再派 */
    public static final int DEFAULT_SUBAGENT_MAX_DEPTH = 1;
    /** 子智能体：默认同时最多 3 个 */
    public static final int DEFAULT_SUBAGENT_MAX_CONCURRENCY = 3;

    @PostConstruct
    public void init() {
        Path file = paths.settingsFile();
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> loaded = mapper.readValue(Files.readString(file),
                    new TypeReference<Map<String, Object>>() {});
                if (loaded != null) {
                    data.putAll(loaded);
                }
                log.info("插件设置已加载: {}（关掉的插件 {} 个）", file, enabledOverrides().size());
            } catch (Exception e) {
                // 【绝不能因为设置文件坏了就起不来】用户手改坏一个 JSON 括号，
                // 整个软件打不开是最糟的失败方式。坏了就当默认设置，并把文件挪到一边留证据。
                log.error("插件设置文件解析失败，本次按默认设置运行: {}", file, e);
                backupBroken(file, e);
            }
        } else {
            log.info("未找到插件设置文件（全部用默认值）: {}", file);
        }
        // 目录不存在就建出来：用户点"打开插件目录"时得有个真实目录可开
        try {
            Files.createDirectories(paths.pluginsDir());
        } catch (IOException e) {
            log.warn("创建插件目录失败（不影响启动）: {}", paths.pluginsDir(), e);
        }
    }

    private void backupBroken(Path file, Exception cause) {
        try {
            Path broken = file.resolveSibling("settings.json.broken");
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已把损坏的设置文件改名为: {}（原因：{}）", broken, cause.getMessage());
        } catch (IOException e) {
            log.warn("备份损坏的设置文件也失败了: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /** 用户显式设置过的开关：插件id -> 是否开启 */
    @SuppressWarnings("unchecked")
    public Map<String, Boolean> enabledOverrides() {
        Object raw = data.get("enabled");
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        ((Map<String, Object>) raw).forEach((k, v) -> {
            if (v instanceof Boolean b) {
                out.put(k, b);
            }
        });
        return out;
    }

    /**
     * 这个插件现在开没开。
     *
     * <p>没被用户点过 → 用插件自己声明的默认值（{@link Plugin#isEnabledByDefault()}）。
     * 这就是"重启后还在"的全部秘密：用户点过的才写进文件，没点过的永远跟着代码走。
     */
    public boolean isEnabled(Plugin plugin) {
        if (plugin == null) {
            return true;
        }
        return isEnabled(plugin.getId(), plugin.isEnabledByDefault());
    }

    /** 按 id 判定（外置插件在实例化失败时只有 id，也得能显示开关状态） */
    public boolean isEnabled(String pluginId, boolean defaultValue) {
        Boolean v = enabledOverrides().get(pluginId);
        return v == null ? defaultValue : v;
    }

    /**
     * 记下用户的选择。
     *
     * <p>与默认值相同就**删掉这条记录**（而不是写一个和默认一样的值）：
     * 少写无用数据，也让"以后改默认值"能生效。
     *
     * @return 落盘后的实际状态
     */
    public synchronized boolean setEnabled(String pluginId, boolean enabled, boolean defaultValue) {
        Map<String, Object> map = new LinkedHashMap<>(enabledMapRaw());
        if (enabled == defaultValue) {
            map.remove(pluginId);
        } else {
            map.put(pluginId, enabled);
        }
        data.put("enabled", map);
        save();
        return enabled;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enabledMapRaw() {
        Object raw = data.get("enabled");
        return raw instanceof Map ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // 通用读写（各插件自己的参数段）
    // ------------------------------------------------------------------

    /** 取整个参数段（只读快照，改它不会影响设置） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        Object raw = data.get(name);
        return raw instanceof Map ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
    }

    /** 合并写入参数段（只覆盖传进来的键，null 值 = 删除该键），写完立即落盘 */
    public synchronized Map<String, Object> updateSection(String name, Map<String, Object> updates) {
        Map<String, Object> merged = section(name);
        if (updates != null) {
            updates.forEach((k, v) -> {
                if (v == null) {
                    merged.remove(k);
                } else {
                    merged.put(k, v);
                }
            });
        }
        data.put(name, merged);
        save();
        return merged;
    }

    /** 直接替换参数段里的一个值（列表用，比如团队成员、自动化任务） */
    public synchronized void put(String section, String key, Object value) {
        Map<String, Object> merged = section(section);
        if (value == null) {
            merged.remove(key);
        } else {
            merged.put(key, value);
        }
        data.put(section, merged);
        save();
    }

    /** 顶层键读写（devMode 这类不属于任何插件段的用） */
    public Object top(String key) {
        return data.get(key);
    }

    public synchronized void putTop(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
        save();
    }

    /** int 型读取：字符串数字也认（配置文件常被手改成 "300"） */
    public int intOf(String section, String key, int fallback) {
        return toInt(section(section).get(key), fallback);
    }

    public boolean boolOf(String section, String key, boolean fallback) {
        Object v = section(section).get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "true", "1", "yes", "是", "开" -> true;
                case "false", "0", "no", "否", "关" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    public String stringOf(String section, String key, String fallback) {
        Object v = section(section).get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    /** 列表读取：元素统一当 Map 用（团队成员、自动化任务都是这种结构） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listOf(String section, String key) {
        Object raw = section(section).get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) item));
            }
        }
        return out;
    }

    private static int toInt(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 全量快照（REST 返回给前端用） */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach((k, v) -> out.put(k, v));
        return out;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /**
     * 原子落盘。
     *
     * <p>先写 {@code settings.json.tmp} 再 move 覆盖：用户点开关的那一刻如果正好强杀进程，
     * 直写会让文件变成半截 JSON，下次启动就"所有设置丢了"。
     * 落盘失败只记日志、不抛异常 —— 设置存不下来是遗憾，不该让这次点击变成 500。</p>
     */
    public synchronized void save() {
        Path file = paths.settingsFile();
        Path tmp = file.resolveSibling("settings.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, mapper.writeValueAsString(data));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存插件设置失败: {}", file, e);
        }
    }

    /** 设置文件路径（REST/文档里显示用） */
    public Path file() {
        return paths.settingsFile();
    }
}

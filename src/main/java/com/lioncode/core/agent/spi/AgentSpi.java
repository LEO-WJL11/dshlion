package com.lioncode.core.agent.spi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 主循环的扩展点（SPI）。
 *
 * <p>【为什么需要这个文件】"一切皆插件"要求技能、@ 引用、终端限制、大循环策略、审批审查、
 * 自动化任务这些能力都能在**不改 AgentLoop 源码**的前提下挂上去。AgentLoop 是核心循环，
 * 被所有功能共用，谁都能改它就会天天冲突；所以这里把所有"想插进主循环"的需求收成 4 个口子，
 * 各插件只实现自己关心的那一个方法（其余走默认实现，等于没插）。</p>
 *
 * <p>调用顺序与次数：每次构建消息时依次调用所有已注册的实现，任何一个抛异常都只记日志、
 * 不影响主流程（插件坏了不能把主循环带崩）。</p>
 */
public interface AgentSpi {

    /** SPI 实现的名字（用于日志和排错）。 */
    default String spiName() {
        return getClass().getSimpleName();
    }

    /** 优先级，数字小的先执行（默认 100；技能注入这类希望排在后面的可以给大一点）。 */
    default int order() {
        return 100;
    }

    /**
     * 往系统提示词末尾追加段落。
     *
     * <p>典型用途：技能目录（让模型自己挑技能）、插件清单、团队智能体说明。</p>
     *
     * @return 追加的段落列表，空列表表示不加
     */
    default List<String> extraSystemSections(String sessionId, String workspacePath, String userMessage) {
        return List.of();
    }

    /**
     * 用户消息进入模型之前的改写。
     *
     * <p>典型用途：@ 文件 / @ 历史对话 展开成真实上下文。</p>
     *
     * @return 改写后的消息（默认原样返回）
     */
    default String transformUserMessage(String sessionId, String userMessage) {
        return userMessage;
    }

    /**
     * 过滤本轮下发给模型的工具名集合。
     *
     * <p>典型用途：插件被用户在设置里关掉后，它提供的工具直接从提示词里消失。</p>
     *
     * @param all 当前模式下本来可用的全部工具名
     * @return 过滤后的工具名集合
     */
    default Set<String> filterToolNames(String sessionId, Set<String> all) {
        return all;
    }

    /**
     * Agent 大循环参数覆盖（大循环插件用）。
     *
     * <p>已约定的 key：{@code maxIterations}（int）、{@code toolTimeoutSeconds}（int）、
     * {@code silentRounds}（int，连续无工具调用的容忍轮数）。未知 key 直接忽略。</p>
     */
    default Map<String, Object> loopOptions(String sessionId) {
        return Map.of();
    }

    // ==================== 静态注册表 ====================

    /** 已注册的实现（CopyOnWrite：热插拔时会在运行中被增删）。 */
    List<AgentSpi> REGISTERED = new CopyOnWriteArrayList<>();

    /** 注册一个扩展（重复注册同一个实例会被忽略）。 */
    static void register(AgentSpi spi) {
        if (spi == null || REGISTERED.contains(spi)) {
            return;
        }
        REGISTERED.add(spi);
        REGISTERED.sort((a, b) -> Integer.compare(a.order(), b.order()));
    }

    /** 注销（插件卸载时调用）。 */
    static void unregister(AgentSpi spi) {
        REGISTERED.remove(spi);
    }

    /** 当前注册数量（测试用）。 */
    static int size() {
        return REGISTERED.size();
    }

    /** 清空（测试用）。 */
    static void clear() {
        REGISTERED.clear();
    }

    /** 依次调用所有实现的 extraSystemSections，异常只记日志。 */
    static List<String> collectSections(String sessionId, String workspacePath, String userMessage) {
        List<String> out = new ArrayList<>();
        for (AgentSpi spi : REGISTERED) {
            try {
                List<String> add = spi.extraSystemSections(sessionId, workspacePath, userMessage);
                if (add != null) {
                    for (String s : add) {
                        if (s != null && !s.isBlank()) {
                            out.add(s);
                        }
                    }
                }
            } catch (Exception e) {
                warn(spi, "extraSystemSections", e);
            }
        }
        return out;
    }

    /** 依次改写用户消息，前一个的输出是后一个的输入。 */
    static String applyTransforms(String sessionId, String userMessage) {
        String current = userMessage;
        for (AgentSpi spi : REGISTERED) {
            try {
                String next = spi.transformUserMessage(sessionId, current);
                if (next != null) {
                    current = next;
                }
            } catch (Exception e) {
                warn(spi, "transformUserMessage", e);
            }
        }
        return current;
    }

    /** 依次过滤工具名集合（用 LinkedHashSet 保持顺序稳定，提示词才好缓存）。 */
    static Set<String> applyToolFilter(String sessionId, Set<String> all) {
        Set<String> current = all;
        for (AgentSpi spi : REGISTERED) {
            try {
                Set<String> next = spi.filterToolNames(sessionId, current);
                if (next != null) {
                    current = new LinkedHashSet<>(next);
                }
            } catch (Exception e) {
                warn(spi, "filterToolNames", e);
            }
        }
        return current;
    }

    /** 合并所有实现的 loopOptions（后注册的覆盖先注册的同名 key，order 小的先写）。 */
    static Map<String, Object> mergedLoopOptions(String sessionId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (AgentSpi spi : REGISTERED) {
            try {
                Map<String, Object> m = spi.loopOptions(sessionId);
                if (m != null && !m.isEmpty()) {
                    // 只接受约定过的 key，别的直接丢，防止插件乱塞把主循环搞坏
                    for (String k : new String[] {"maxIterations", "toolTimeoutSeconds", "silentRounds"}) {
                        if (m.containsKey(k)) {
                            out.put(k, m.get(k));
                        }
                    }
                }
            } catch (Exception e) {
                warn(spi, "loopOptions", e);
            }
        }
        return out;
    }

    /** 取 int 型 loopOption，没配或类型不对就用兜底值。 */
    static int loopInt(String sessionId, String key, int fallback) {
        Object v = mergedLoopOptions(sessionId).get(key);
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

    private static void warn(AgentSpi spi, String method, Exception e) {
        System.err.println("[AgentSpi] 插件 " + spi.spiName() + " 的 " + method
            + " 抛异常，已跳过：" + e);
    }
}

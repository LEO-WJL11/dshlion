package com.lioncode.core.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用的「别再来一遍」守卫。
 *
 * <p>存在的原因是一次真实跑测试（用户要求"把所有工具都调用一遍"）暴露的两个坑，
 * 两个都不是模型"笨"，而是 harness 没兜住：
 *
 * <ol>
 *   <li><b>同样的调用重复做</b>：{@code create_directory} 连着成功 7 次，
 *       参数一模一样 —— 第二次开始就不可能拿到新信息，纯烧时间（本地模型一轮 10~40 秒）。</li>
 *   <li><b>失败后硬试</b>：{@code fetch_url} 连续失败 40 多次；
 *       那时每个网络工具 connect 15s + read 30s，一条消息的 10 分钟上限就被这么耗光了，
 *       用户看到的是"❌ 处理超时"，而不是"某个工具在死循环"。</li>
 * </ol>
 *
 * <p>规则（按会话统计，每条用户消息开始时重置）：
 * <ul>
 *   <li>同样的「工具 + 参数」第 {@value #REPEAT_SKIP_AT} 次起不再执行，改成给模型一句纠正提示；</li>
 *   <li>到第 {@value #REPEAT_ABORT_AT} 次还在重复 → 直接终止任务，别拖到超时；</li>
 *   <li>同一个工具连续失败 {@value #FAIL_SKIP_AT} 次后不再执行，提示它换做法。</li>
 * </ul>
 *
 * <p>抽成独立类是为了能直接跑自测：
 * {@code java -cp target/classes com.lioncode.core.agent.ToolCallGuard}
 */
public class ToolCallGuard {

    /** 第几次同样的调用开始"只提示不执行" */
    public static final int REPEAT_SKIP_AT = 3;
    /** 第几次同样的调用直接终止任务 */
    public static final int REPEAT_ABORT_AT = 5;
    /** 同一工具连续失败几次后"只提示不执行" */
    public static final int FAIL_SKIP_AT = 6;
    /** 同一工具连续失败几次时先提醒一次 */
    public static final int FAIL_WARN_AT = 3;

    public enum Verdict {
        /** 正常执行 */
        OK,
        /** 别执行了，给模型一句纠正提示 */
        SKIP,
        /** 重复得太离谱，直接结束任务 */
        ABORT
    }

    /** 判定结果：verdict + 给模型/用户看的说明 */
    public record Decision(Verdict verdict, String hint, int count) {
        public static Decision ok() {
            return new Decision(Verdict.OK, null, 0);
        }
    }

    /** sessionId -> (工具名+参数的指纹 -> 次数) */
    private final Map<String, Map<String, Integer>> repeats = new ConcurrentHashMap<>();
    /** sessionId -> (工具名 -> 连续失败次数) */
    private final Map<String, Map<String, Integer>> failures = new ConcurrentHashMap<>();

    /** 每条用户消息开始时调用，跨消息不累计（上一条消息里调用过，这一条当然可以再调用） */
    public void reset(String sessionId) {
        repeats.remove(sessionId);
        failures.remove(sessionId);
    }

    /**
     * 执行前问一句：这次调用还要不要真的跑？
     *
     * @param sessionId 会话
     * @param toolName  工具名
     * @param argsJson  参数（已序列化，用来判断"是不是一模一样"）
     */
    public Decision beforeCall(String sessionId, String toolName, String argsJson) {
        if (sessionId == null || toolName == null) {
            return Decision.ok();
        }
        String key = toolName + "|" + (argsJson == null ? "" : argsJson.trim());

        int count = repeats
            .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .merge(key, 1, Integer::sum);

        if (count >= REPEAT_ABORT_AT) {
            return new Decision(Verdict.ABORT,
                "⏹ 已停止：同一个工具用同样的参数被调用了 " + count + " 次（" + toolName
                + "），结果不会变。任务终止，避免继续空转。", count);
        }
        if (count >= REPEAT_SKIP_AT) {
            return new Decision(Verdict.SKIP,
                "【系统提示】" + toolName + " 用**完全相同的参数**已经调用过 " + (count - 1)
                + " 次了，结果不会变，这次没有执行。请换做法：改参数、换工具，或者直接用已有结果回答。", count);
        }

        int fails = failureCount(sessionId, toolName);
        if (fails >= FAIL_SKIP_AT) {
            return new Decision(Verdict.SKIP,
                "【系统提示】" + toolName + " 已经连续失败 " + fails + " 次，这次没有执行。"
                + "先仔细看上一次的错误信息：是缺参数、参数名写错了，还是这个工具在这台机器上根本用不了？"
                + "改不对就换个工具或直接说明做不到，不要继续硬试。", count);
        }
        if (fails == FAIL_WARN_AT) {
            // 只在刚好到阈值时提醒一次，别每轮都念
            return new Decision(Verdict.OK,
                "【系统提示】" + toolName + " 已经连续失败 " + fails + " 次了。"
                + "请照着错误信息把参数改成工具要求的名字/格式再试；再不行就换工具。", count);
        }
        return Decision.ok();
    }

    /** 执行结果回报（成功清零连续失败计数） */
    public void afterCall(String sessionId, String toolName, boolean success) {
        if (sessionId == null || toolName == null) {
            return;
        }
        Map<String, Integer> m = failures.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        if (success) {
            m.remove(toolName);
        } else {
            m.merge(toolName, 1, Integer::sum);
        }
    }

    /** 连续失败次数（测试/日志用） */
    public int failureCount(String sessionId, String toolName) {
        Map<String, Integer> m = failures.get(sessionId);
        if (m == null) {
            return 0;
        }
        Integer v = m.get(toolName);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------
    // 自测：java -cp target/classes com.lioncode.core.agent.ToolCallGuard
    // ------------------------------------------------------------------
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        ToolCallGuard g = new ToolCallGuard();
        String s = "s1";

        // 1) 第一次、第二次照常执行（可能是合理的重试）
        check("第 1 次同样调用 → 执行", g.beforeCall(s, "create_directory", "{\"path\":\"a\"}").verdict() == Verdict.OK);
        check("第 2 次同样调用 → 执行", g.beforeCall(s, "create_directory", "{\"path\":\"a\"}").verdict() == Verdict.OK);
        // 2) 第三次开始只提示不执行
        Decision d3 = g.beforeCall(s, "create_directory", "{\"path\":\"a\"}");
        check("第 3 次同样调用 → 跳过执行", d3.verdict() == Verdict.SKIP);
        check("跳过时带纠正提示", d3.hint() != null && d3.hint().contains("完全相同的参数"));
        // 3) 参数不同就是新调用
        check("参数不同 → 照常执行",
            g.beforeCall(s, "create_directory", "{\"path\":\"b\"}").verdict() == Verdict.OK);
        // 4) 第五次同样调用 → 终止
        g.beforeCall(s, "create_directory", "{\"path\":\"a\"}");
        Decision d5 = g.beforeCall(s, "create_directory", "{\"path\":\"a\"}");
        check("第 5 次同样调用 → 终止任务", d5.verdict() == Verdict.ABORT);

        // 5) 连续失败：失败 3 次后第 4 次调用先提醒（仍执行）；失败 6 次后不再执行
        //    语义：计数是"已经失败过几次"，所以提醒出现在第 4 次调用、拦下出现在第 7 次调用
        ToolCallGuard g2 = new ToolCallGuard();
        String s2 = "s2";
        for (int i = 1; i <= 3; i++) {           // 失败 3 次
            check("失败第 " + i + " 次仍允许执行",
                g2.beforeCall(s2, "number_convert", "{\"v\":" + i + "}").verdict() == Verdict.OK);
            g2.afterCall(s2, "number_convert", false);
        }
        Decision warn = g2.beforeCall(s2, "number_convert", "{\"v\":4}");
        check("连续失败 3 次后 → 提醒一次但仍执行",
            warn.verdict() == Verdict.OK && warn.hint() != null && warn.hint().contains("连续失败"));
        g2.afterCall(s2, "number_convert", false);   // 第 4 次也失败
        for (int i = 5; i <= 6; i++) {               // 第 5、6 次失败
            g2.beforeCall(s2, "number_convert", "{\"v\":" + i + "}");
            g2.afterCall(s2, "number_convert", false);
        }
        Decision stop = g2.beforeCall(s2, "number_convert", "{\"v\":7}");
        check("连续失败 6 次后 → 不再执行", stop.verdict() == Verdict.SKIP);
        check("拦截提示里点名了工具和次数",
            stop.hint() != null && stop.hint().contains("number_convert") && stop.hint().contains("6"));
        g2.afterCall(s2, "number_convert", true);
        check("成功后失败计数清零",
            g2.failureCount(s2, "number_convert") == 0
            && g2.beforeCall(s2, "number_convert", "{\"v\":8}").verdict() == Verdict.OK);

        // 6) reset 之后不再累计（新的一条用户消息）
        ToolCallGuard g3 = new ToolCallGuard();
        g3.beforeCall("s3", "x", "{}");
        g3.beforeCall("s3", "x", "{}");
        g3.reset("s3");
        check("reset 后重新计数", g3.beforeCall("s3", "x", "{}").verdict() == Verdict.OK);

        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label);
    }
}

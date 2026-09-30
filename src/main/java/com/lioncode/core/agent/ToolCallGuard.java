package com.lioncode.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用的「重复/连续失败」统计器。
 *
 * <p><b>2026-09-30 起它不再拦任何调用</b>：用户明确要求"能把任务中断的东西都删掉"，
 * 于是"同样参数 5 次就终止任务""重复 3 次/连续失败 6 次就跳过不执行"这些行为全部删除，
 * 只保留计数（排查用）。要不要继续尝试由模型判断，停不停由用户按界面上的 ⏹ 决定。
 *
 * <p>下面这段是它当初为什么存在（留着当背景，别再照着它把拦人逻辑加回来）：
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

    private static final Logger log = LoggerFactory.getLogger(ToolCallGuard.class);


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
        // 【用户要求】这里原来是"同一工具同参数 5 次就**终止任务**、3 次就不再执行、
        // 连续失败 6 次也不再执行，还往对话里插【系统提示】"。用户明确要求：
        // **能把任务中断的东西都删掉** —— 于是这里只统计（日志用），永远放行。
        //
        // 判断该不该继续尝试是模型的事，停不停是用户按界面上的 ⏹ 的事；
        // 我们不在背后替他们做决定，也不往对话里塞系统提示。
        if (sessionId != null && toolName != null) {
            String key = toolName + "|" + (argsJson == null ? "" : argsJson.trim());
            int n = repeats
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .merge(key, 1, Integer::sum);
            if (n > 1 && log.isDebugEnabled()) {
                log.debug("同一调用第 {} 次（不拦，照常执行）: {}", n, key);
            }
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
    private static int failed = 0;   // 注意：与上面方法里的局部变量重名，见下

    public static void main(String[] args) {
        ToolCallGuard g = new ToolCallGuard();
        String s = "s1";

        // 【现状】不再拦、不再终止：同样的调用问 10 次，10 次都要放行（用户要求删掉中断）
        boolean allOk = true;
        for (int i = 1; i <= 10; i++) {
            Decision d = g.beforeCall(s, "git_status", "{\"path\":\"repo\"}");
            if (d.verdict() != Verdict.OK || d.hint() != null) {
                allOk = false;
                System.out.println("  第 " + i + " 次被拦了: " + d.verdict() + " / " + d.hint());
            }
        }
        check("同一调用连问 10 次都放行（不再跳过、不再终止任务）", allOk);

        // 连续失败：以前 6 次之后就不执行了，现在照样放行
        for (int i = 0; i < 10; i++) {
            g.afterCall(s, "number_convert", false);
        }
        Decision dec = g.beforeCall(s, "number_convert", "{\"v\":7}");
        check("连续失败 10 次后仍然放行（不再跳过、不再插【系统提示】）",
            dec.verdict() == Verdict.OK && dec.hint() == null);
        check("连续失败次数还是照常统计（日志/排查用）", g.failureCount(s, "number_convert") == 10);

        g.afterCall(s, "number_convert", true);
        check("成功一次就把连续失败清零", g.failureCount(s, "number_convert") == 0);

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

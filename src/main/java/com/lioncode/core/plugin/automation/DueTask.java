package com.lioncode.core.plugin.automation;

import java.time.Instant;

/**
 * "这条任务该跑了" —— {@link AutomationPlugin#pollDue} 的返回值。
 *
 * @param task   到点的任务
 * @param reason 为什么算它到点（日志与界面提示用）
 * @param dueAt  判定时用的时刻，调用方拿去调 {@code markRun(taskId, dueAt)}
 */
public record DueTask(AutomationTask task, String reason, Instant dueAt) {

    /** 要在哪个会话里跑 */
    public String sessionId() {
        return task.sessionId();
    }

    /** 要发给模型的指令 */
    public String prompt() {
        return task.prompt();
    }
}

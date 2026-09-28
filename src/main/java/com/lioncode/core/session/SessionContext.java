package com.lioncode.core.session;

/**
 * 工具执行期间的会话上下文（线程级）
 *
 * 为什么需要它：{@code ToolPlugin.execute(arguments)} 的签名里没有会话信息，
 * 但有些工具天然需要知道"现在是哪个会话在调我"——比如向用户提问的工具，
 * 它得把问题投递给正确的会话、并等那条会话的用户来回答。
 *
 * AgentLoop 在执行工具前把当前会话 ID 放进本 ThreadLocal，执行完清掉；
 * 工具通过 {@link #get()} 取。线程模型是安全的：SessionDispatcher 把同一个会话的
 * 任务串行投递到固定的 worker 线程上执行，工具调用是同步的，不会跨线程串味。
 *
 * 这个类刻意做成纯静态工具类（不是 Spring Bean），照的是同目录隔壁
 * {@code WorkspaceContext} 的写法。
 */
public final class SessionContext {

    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();

    private SessionContext() {
    }

    /** 设置当前线程正在处理的会话 ID */
    public static void set(String sessionId) {
        SESSION.set(sessionId);
    }

    /** 取当前线程正在处理的会话 ID；不在工具执行上下文里时为 null */
    public static String get() {
        return SESSION.get();
    }

    /** 清除当前线程的会话上下文 */
    public static void clear() {
        SESSION.remove();
    }
}

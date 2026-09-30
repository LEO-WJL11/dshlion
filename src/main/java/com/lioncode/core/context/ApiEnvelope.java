package com.lioncode.core.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新端点的响应外壳（技能 / @ 引用这两组接口用）。
 *
 * <p>【为什么长这样】项目里老的接口统一是 {@code ApiResponse}：
 * {@code {success, message, data, error}}，前端到处都在按 {@code res.success} / {@code res.data} 取。
 * 而技能和 @ 引用这两组接口的设计稿里写的是 {@code {ok, skills:[...]}} / {@code {ok, items:[...]}}
 * —— 两种写法都有人按着写代码。</p>
 *
 * <p>与其赌一边，不如两边都给：顶层同时放 {@code ok}、{@code success} 和那份数据
 * （{@code skills} / {@code items}），另外在 {@code data} 里再放一份完整副本。
 * 这样 {@code res.ok}、{@code res.success}、{@code res.skills}、{@code res.data.skills}
 * 四种写法都能取到值，任何一边的前端代码都不会因为外壳判断错而白屏。</p>
 */
public final class ApiEnvelope {

    private ApiEnvelope() {
    }

    /**
     * 成功响应。
     *
     * @param fields 要放在顶层的业务字段（例如 skills、items、count），会同时复制进 data
     */
    public static Map<String, Object> ok(Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("success", true);
        out.put("message", "操作成功");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        if (fields != null) {
            out.putAll(fields);
            data.putAll(fields);
        }
        out.put("data", data);
        return out;
    }

    /** 带自定义提示语的成功响应。 */
    public static Map<String, Object> ok(String message, Map<String, Object> fields) {
        Map<String, Object> out = ok(fields);
        out.put("message", message);
        return out;
    }

    /** 失败响应：ok/success 都是 false，error 里是能照着改的原因。 */
    public static Map<String, Object> error(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("success", false);
        out.put("error", error);
        return out;
    }
}

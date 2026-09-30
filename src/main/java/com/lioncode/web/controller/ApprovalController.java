package com.lioncode.web.controller;

import com.lioncode.approval.ApprovalPolicy;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工具审批策略管理接口
 * 
 * 审批策略已在AgentLoop执行链路中强制生效：
 * - AUTO_APPROVE: 直接执行（默认）
 * - CONFIRM: 拒绝执行并提示（用户可改为自动批准）
 * - BLOCK: 禁止执行
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalPolicy approvalPolicy;
    private final PluginRegistry pluginRegistry;

    public ApprovalController(ApprovalPolicy approvalPolicy, PluginRegistry pluginRegistry) {
        this.approvalPolicy = approvalPolicy;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 获取所有工具的审批策略
     */
    @GetMapping
    public ApiResponse<List<ToolPolicyInfo>> getPolicies() {
        List<ToolPolicyInfo> list = pluginRegistry.getToolPlugins().stream()
            .map(tool -> new ToolPolicyInfo(
                tool.getId(),
                tool.getName(),
                tool.getDescription(),
                approvalPolicy.getPolicy(tool.getId()).name()))
            .toList();
        return ApiResponse.ok(list);
    }

    /**
     * 设置单个工具的审批策略
     */
    @PostMapping("/{toolId}")
    public ApiResponse<String> setPolicy(@PathVariable("toolId") String toolId,
                                         @RequestBody SetPolicyRequest request) {
        if (pluginRegistry.getById(toolId).isEmpty()) {
            return ApiResponse.error("工具不存在: " + toolId);
        }
        // policy 缺失时 Enum.valueOf(null) 抛 NPE（不是 IllegalArgumentException），
        // 原来的 catch 拦不住 → 500。显式判掉。
        if (request == null || request.policy() == null || request.policy().isBlank()) {
            return ApiResponse.error("缺少策略（可选: AUTO_APPROVE / CONFIRM / BLOCK）");
        }
        try {
            ApprovalPolicy.ToolPolicy policy = ApprovalPolicy.ToolPolicy.valueOf(
                request.policy().trim().toUpperCase(java.util.Locale.ROOT));
            approvalPolicy.setToolPolicy(toolId, policy);
            return ApiResponse.ok("策略已更新", policy.name());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效策略: " + request.policy()
                + "（可选: AUTO_APPROVE / CONFIRM / BLOCK）");
        }
    }

    /**
     * 批量设置策略
     */
    @PostMapping
    public ApiResponse<Map<String, String>> setPolicies(@RequestBody Map<String, String> policies) {
        if (policies == null || policies.isEmpty()) {
            return ApiResponse.error("请求体为空，应形如 {\"工具id\":\"策略\"}");
        }
        int updated = 0;
        int skipped = 0;
        for (Map.Entry<String, String> entry : policies.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                skipped++;
                continue;
            }
            try {
                // valueOf(null) 抛 NPE：原来 catch 的是 IllegalArgumentException，null 值会直接 500
                ApprovalPolicy.ToolPolicy policy = ApprovalPolicy.ToolPolicy.valueOf(
                    entry.getValue().trim().toUpperCase(java.util.Locale.ROOT));
                if (pluginRegistry.getById(entry.getKey()).isPresent()) {
                    approvalPolicy.setToolPolicy(entry.getKey(), policy);
                    updated++;
                } else {
                    skipped++;
                }
            } catch (IllegalArgumentException ignored) {
                skipped++;   // 跳过无效策略
            }
        }
        return ApiResponse.ok("已更新 " + updated + " 个工具的策略"
            + (skipped > 0 ? "（跳过 " + skipped + " 项无效/不存在的工具）" : ""), null);
    }

    /**
     * 工具策略信息
     */
    public record ToolPolicyInfo(String toolId, String toolName, String description, String policy) {}

    /**
     * 设置策略请求
     */
    public record SetPolicyRequest(String policy) {}
}

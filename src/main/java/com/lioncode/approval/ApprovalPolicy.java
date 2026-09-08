package com.lioncode.approval;

import com.lioncode.core.plugin.tool.ToolPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批策略管理器
 * 
 * 管理工具执行的审批策略，已接入AgentLoop执行链路：
 * - 自动批准：直接执行
 * - 需要确认：拒绝执行并明确提示（用户可在设置中把该工具改为自动批准）
 * - 禁止执行：拒绝执行
 * 
 * 默认策略：所有工具自动批准（保持开箱即用），
 * 用户可通过 /api/approvals 接口或设置面板收紧特定工具的审批策略。
 */
@Component
public class ApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPolicy.class);

    /** 自动批准的工具ID（只读操作默认在此集合） */
    private final Set<String> autoApprovedTools = ConcurrentHashMap.newKeySet();

    /** 需要确认的工具ID */
    private final Set<String> requiresConfirmationTools = ConcurrentHashMap.newKeySet();

    /** 禁止的工具ID */
    private final Set<String> blockedTools = ConcurrentHashMap.newKeySet();

    public ApprovalPolicy() {
        // 默认自动批准的工具（只读操作，显式登记以便查询）
        autoApprovedTools.addAll(Set.of(
            "tool.file.read", "tool.file.list", "tool.file.search", "tool.file.glob",
            "tool.file.info", "tool.file.headtail", "tool.file.wc", "tool.file.linecount",
            "tool.file.tree", "tool.git.status", "tool.git.log", "tool.git.diff",
            "tool.git.remote", "tool.system.info", "tool.system.env", "tool.system.cwd",
            "tool.code.json", "tool.code.regex", "tool.code.base64", "tool.code.hash",
            "tool.code.uuid", "tool.code.timestamp", "tool.code.diff", "tool.code.string",
            "tool.code.cron", "tool.code.number", "tool.code.markdown", "tool.code.escape",
            "tool.web.search", "tool.http.get", "tool.web.fetch", "tool.web.dns"
        ));
        // 注意：写操作（write/shell/git.commit等）默认不在任何集合中，
        // 按"未知默认自动批准"规则处理，保证Agent开箱可用；
        // 需要收紧时由用户显式设置 CONFIRM / BLOCK。
    }

    /**
     * 检查工具是否需要审批（已接入AgentLoop.executeTool）
     */
    public ApprovalResult checkApproval(String toolId) {
        if (blockedTools.contains(toolId)) {
            return ApprovalResult.blocked("工具已被禁止: " + toolId);
        }
        if (requiresConfirmationTools.contains(toolId)) {
            return ApprovalResult.requiresConfirmation(
                "工具需要用户确认: " + toolId + "（请在设置中将其改为自动批准，或保持禁止）");
        }
        // 显式登记的只读工具 + 未登记工具均自动批准（开箱即用）
        return ApprovalResult.autoApproved();
    }

    /**
     * 获取工具当前生效的审批策略（供查询接口/设置面板使用）
     */
    public ToolPolicy getPolicy(String toolId) {
        if (blockedTools.contains(toolId)) return ToolPolicy.BLOCK;
        if (requiresConfirmationTools.contains(toolId)) return ToolPolicy.CONFIRM;
        return ToolPolicy.AUTO_APPROVE;
    }

    /**
     * 设置工具审批策略
     */
    public void setToolPolicy(String toolId, ToolPolicy policy) {
        autoApprovedTools.remove(toolId);
        requiresConfirmationTools.remove(toolId);
        blockedTools.remove(toolId);

        switch (policy) {
            case AUTO_APPROVE -> autoApprovedTools.add(toolId);
            case CONFIRM -> requiresConfirmationTools.add(toolId);
            case BLOCK -> blockedTools.add(toolId);
        }
        log.info("工具审批策略已更新: {} -> {}", toolId, policy);
    }

    /**
     * 批量获取工具策略（用于设置面板一次性展示）
     */
    public Map<String, ToolPolicy> getPolicies(Iterable<ToolPlugin> tools) {
        Map<String, ToolPolicy> result = new ConcurrentHashMap<>();
        for (ToolPlugin tool : tools) {
            result.put(tool.getId(), getPolicy(tool.getId()));
        }
        return result;
    }

    /**
     * 审批结果
     */
    public record ApprovalResult(
        ApprovalAction action,
        String reason
    ) {
        public static ApprovalResult autoApproved() {
            return new ApprovalResult(ApprovalAction.AUTO_APPROVE, null);
        }

        public static ApprovalResult requiresConfirmation(String reason) {
            return new ApprovalResult(ApprovalAction.CONFIRM, reason);
        }

        public static ApprovalResult blocked(String reason) {
            return new ApprovalResult(ApprovalAction.BLOCK, reason);
        }
    }

    /**
     * 审批动作
     */
    public enum ApprovalAction {
        AUTO_APPROVE("自动批准"),
        CONFIRM("需要确认"),
        BLOCK("禁止执行");

        private final String displayName;
        ApprovalAction(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /**
     * 工具策略
     */
    public enum ToolPolicy {
        AUTO_APPROVE,
        CONFIRM,
        BLOCK
    }
}

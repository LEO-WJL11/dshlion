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
 * 管理工具执行的审批策略：
 * - 自动批准：低风险操作
 * - 需要确认：中风险操作
 * - 禁止执行：高风险操作
 */
@Component
public class ApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPolicy.class);

    /** 自动批准的工具ID */
    private final Set<String> autoApprovedTools = ConcurrentHashMap.newKeySet();

    /** 需要确认的工具ID */
    private final Set<String> requiresConfirmationTools = ConcurrentHashMap.newKeySet();

    /** 禁止的工具ID */
    private final Set<String> blockedTools = ConcurrentHashMap.newKeySet();

    public ApprovalPolicy() {
        // 默认自动批准的工具（只读操作）
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

        // 需要确认的工具（写操作）
        requiresConfirmationTools.addAll(Set.of(
            "tool.file.write", "tool.file.modify", "tool.file.delete",
            "tool.file.copy", "tool.file.move", "tool.file.mkdir", "tool.file.touch",
            "tool.file.append", "tool.file.chmod", "tool.shell.execute",
            "tool.git.commit", "tool.git.branch", "tool.git.stash", "tool.git.init",
            "tool.http.post", "tool.web.download"
        ));
    }

    /**
     * 检查工具是否需要审批
     */
    public ApprovalResult checkApproval(String toolId) {
        if (blockedTools.contains(toolId)) {
            return ApprovalResult.blocked("工具已被禁止: " + toolId);
        }
        if (autoApprovedTools.contains(toolId)) {
            return ApprovalResult.autoApproved();
        }
        if (requiresConfirmationTools.contains(toolId)) {
            return ApprovalResult.requiresConfirmation("工具需要用户确认: " + toolId);
        }
        // 默认需要确认
        return ApprovalResult.requiresConfirmation("未知工具需要确认: " + toolId);
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

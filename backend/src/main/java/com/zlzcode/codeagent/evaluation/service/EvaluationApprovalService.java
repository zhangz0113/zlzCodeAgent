package com.zlzcode.codeagent.evaluation.service;

import com.zlzcode.codeagent.agent.dto.RunResponse;
import com.zlzcode.codeagent.agent.model.ApprovalRecord;
import com.zlzcode.codeagent.agent.service.ApprovalService;
import com.zlzcode.codeagent.agent.store.ApprovalStore;
import com.zlzcode.codeagent.evaluation.model.EvaluationCase.ApprovalRule;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvaluationApprovalService {
    private final ApprovalService service;
    private final ApprovalStore store;
    private final EvaluationEvidenceService evidence;
    private final Map<String, DecisionEvidence> decisions = new LinkedHashMap<>();
    private final AntPathMatcher matcher = new AntPathMatcher("/");

    public EvaluationApprovalService(ApprovalService service, ApprovalStore store, EvaluationEvidenceService evidence) {
        this.service = service;
        this.store = store;
        this.evidence = evidence;
    }

    /*
     * 背景：无人值守评测需要回应修改审批，但不能扩大日常工作区权限。
     * 设计意图：校验当前审批身份与案例规则后只提交决定，仍让原运行时提交固定计划。
     * 关键约束：必须检查 MOVE 两端和 MKDIR 的全部路径；不能直接执行模型参数或把批准等同于提交成功。
     */
    public void decide(String workspaceId, RunResponse run, List<ApprovalRule> rules, Path root) throws IOException {
        ApprovalRecord record = store.read(run.sessionId(), run.runId(), run.pendingApprovalId());
        if (!workspaceId.equals(record.workspaceId()) || !run.pendingToolCallId().equals(record.toolCallId())
                || !run.runId().equals(record.plan().runId()) || !record.toolCallId().equals(record.plan().toolCallId())) {
            throw new IOException("Approval does not belong to the current attempt");
        }
        if (decisions.containsKey(record.approvalId())) return;
        boolean allowed = allows(rules, record.plan().operation().name(), record.plan().relativePaths());
        String reason = allowed ? "All planned paths and the operation match the case authorization"
                : "The case does not authorize every planned path and operation";
        service.decide(run.sessionId(), run.runId(), record.approvalId(),
                allowed ? ApprovalRecord.Decision.APPROVE : ApprovalRecord.Decision.REJECT);
        decisions.put(record.approvalId(), new DecisionEvidence(reason, store.read(run.sessionId(), run.runId(), record.approvalId())));
        refresh(root);
    }

    public boolean allows(List<ApprovalRule> rules, String operation, List<String> paths) {
        if (paths.isEmpty()) return false;
        for (String raw : paths) {
            String path = raw.replace('\\', '/');
            if (path.startsWith("/") || path.contains(":") || List.of(path.split("/")).contains("..")) return false;
            List<ApprovalRule> applicable = rules.stream().filter(rule -> rule.operations().contains(operation)).toList();
            if (applicable.stream().flatMap(rule -> rule.exclude().stream()).anyMatch(pattern -> matcher.match(pattern, path))) return false;
            if (applicable.stream().flatMap(rule -> rule.include().stream()).noneMatch(pattern -> matcher.match(pattern, path))) return false;
        }
        return true;
    }

    public void refresh(Path root) throws IOException {
        for (var entry : decisions.entrySet()) {
            ApprovalRecord previous = entry.getValue().record();
            ApprovalRecord current = store.read(previous.sessionId(), previous.runId(), previous.approvalId());
            evidence.write(root.resolve("approvals").resolve(entry.getKey() + ".json"), new DecisionEvidence(entry.getValue().reason(), current));
        }
    }

    public record DecisionEvidence(String reason, ApprovalRecord record) { }
}

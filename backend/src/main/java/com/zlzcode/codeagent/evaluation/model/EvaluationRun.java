package com.zlzcode.codeagent.evaluation.model;

import com.zlzcode.codeagent.agent.dto.RunResponse;
import com.zlzcode.codeagent.evaluation.model.VerificationResult.Status;

import java.util.List;
import java.util.Map;

public final class EvaluationRun {
    private EvaluationRun() { }

    public record StepInput(String id, String session, String prompt) { }

    public record WorkerRequest(int schemaVersion, String attemptId, String attemptDirectory, String workspace,
                                EvaluationRunConfig config, List<StepInput> steps,
                                List<EvaluationCase.ApprovalRule> approvalRules) { }

    public record StepResult(String id, String session, String sessionId, String status,
                             RunResponse run, String evidencePath) { }

    public record WorkerResult(int schemaVersion, String attemptId, String startedAt, String completedAt,
                               List<StepResult> steps, Map<String, Object> effectiveConfiguration,
                               List<String> missingEvidence, String error) { }

    public record Attempt(String batchId, int attemptIndex, PreparedEvaluation prepared,
                          EvaluationCase definition, EvaluationRunConfig config,
                          String agentJarSha256, String casePackSha256) { }

    public record BatchEntry(String caseId, int caseVersion, int attemptIndex, String attemptId,
                             String status, String resultPath) { }

    public record Batch(int schemaVersion, String batchId, EvaluationSuite suite, EvaluationRunConfig config,
                        String agentJarSha256, String casePackSha256, String startedAt, String completedAt,
                        List<BatchEntry> attempts) { }

    public record Change(String path, String kind, String beforeSha256, String afterSha256) { }

    public record Manifest(int schemaVersion, Attempt attempt, WorkerResult execution,
                           List<String> missingEvidence, String metricScope, long runDurationMs,
                           long verificationDurationMs, long totalDurationMs) { }

    public record Result(String attemptId, String caseId, int caseVersion, Status status, String reasonCode,
                         String detail, List<String> pendingChecks, String reviewId,
                         String verificationPath, String evaluatedAt) { }
}

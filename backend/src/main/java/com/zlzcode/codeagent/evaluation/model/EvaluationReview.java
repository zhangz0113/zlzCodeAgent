package com.zlzcode.codeagent.evaluation.model;

import com.zlzcode.codeagent.evaluation.model.VerificationResult.Status;

import java.util.List;

public record EvaluationReview(String attemptId, String caseId, int caseVersion, String initialResultSha256,
                               String reviewer, String comment, List<Check> checks,
                               ExecutionClassification executionClassification) {
    public EvaluationReview {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public record Check(String id, Status status, String comment, List<String> evidence) { }

    public record ExecutionClassification(Status status, String reason, List<String> evidence) { }

    public record Stored(String reviewId, String createdAt, EvaluationReview input) { }
}

package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationCase;
import com.zlzcode.codeagent.evaluation.model.EvaluationReview;
import com.zlzcode.codeagent.evaluation.model.EvaluationRun.*;
import com.zlzcode.codeagent.evaluation.model.VerificationResult;
import com.zlzcode.codeagent.evaluation.model.VerificationResult.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class EvaluationResultService {
    private static final Set<String> EXECUTION_ERRORS = Set.of("LLM_AUTH_FAILED", "LLM_CONNECTION_FAILED", "LLM_RATE_LIMITED",
            "LLM_UPSTREAM_ERROR", "LLM_MODEL_NOT_FOUND", "LLM_TIMEOUT", "LLM_STREAM_BROKEN",
            "CONTEXT_RESULT_STORE_FAILED", "CONTEXT_RESULT_READ_FAILED", "RUN_STATE_CORRUPTED", "WORKSPACE_UNAVAILABLE");
    private static final Set<String> EXECUTION_FAILURES = Set.of("AGENT_RUN_TIMEOUT", "CONTEXT_CAPACITY_EXCEEDED");
    private final ObjectMapper mapper;
    private final EvaluationEvidenceService files;

    public EvaluationResultService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.files = new EvaluationEvidenceService(mapper);
    }

    /*
     * 背景：Run 成功只代表流程结束，失败执行的部分产物也可能碰巧通过独立测试。
     * 设计意图：先判断必要执行步骤，再合并自动与人工验收，保留三份独立事实。
     * 关键约束：不能让局部 PASS 覆盖未完成的执行，或让人工检查洗掉明确的自动失败。
     */
    public Result evaluate(Attempt attempt, WorkerResult worker, VerificationResult verification,
                           String verificationPath, EvaluationReview review, String reviewId) {
        if (worker == null || worker.error() != null) {
            return result(attempt, Status.ERROR, "WORKER_ERROR", worker == null ? "Worker result is missing" : worker.error(), List.of(), reviewId, verificationPath);
        }
        StepResult failed = worker.steps().stream().filter(step -> "FAILED".equals(step.status())).findFirst().orElse(null);
        if (failed != null) {
            String code = failed.run() == null ? null : failed.run().errorCode();
            if (code != null && EXECUTION_ERRORS.contains(code)) return result(attempt, Status.ERROR, code, "Execution environment failed", List.of(), reviewId, verificationPath);
            if (code != null && EXECUTION_FAILURES.contains(code)) return result(attempt, Status.FAIL, code, "Execution did not satisfy its configured limits", List.of(), reviewId, verificationPath);
            if (review != null && review.executionClassification() != null) {
                return result(attempt, review.executionClassification().status(), "EXECUTION_REVIEWED",
                        review.executionClassification().reason(), List.of(), reviewId, verificationPath);
            }
            return result(attempt, Status.PENDING_REVIEW, "EXECUTION_UNCLASSIFIED", "Execution failed with code " + code,
                    List.of("execution-classification"), reviewId, verificationPath);
        }
        int expected = attempt.definition().steps().isEmpty() ? 1 : attempt.definition().steps().size();
        if (worker.steps().size() != expected || worker.steps().stream().anyMatch(step -> !"SUCCEEDED".equals(step.status()) || step.run() == null)) {
            return result(attempt, Status.ERROR, "INCOMPLETE_EXECUTION", "Required execution evidence is incomplete", List.of(), reviewId, verificationPath);
        }
        if (attempt.definition().verifier() != null) {
            if (verification == null || verification.status() == Status.ERROR) {
                return result(attempt, Status.ERROR, "VERIFICATION_ERROR", verification == null ? "Verification did not run" : verification.error(), List.of(), reviewId, verificationPath);
            }
            if (verification.status() == Status.FAIL || verification.checks().stream().anyMatch(check -> !check.passed())) {
                return result(attempt, Status.FAIL, "CHECK_FAILED", "An independent check failed", List.of(), reviewId, verificationPath);
            }
        }
        if (review != null && review.checks().stream().anyMatch(check -> check.status() == Status.FAIL)) {
            return result(attempt, Status.FAIL, "MANUAL_CHECK_FAILED", "A required manual check failed", List.of(), reviewId, verificationPath);
        }
        List<String> pending = new ArrayList<>();
        if (review == null) attempt.definition().manualChecks().forEach(check -> pending.add(check.id()));
        if (verification != null && verification.status() == Status.PENDING_REVIEW && attempt.definition().manualChecks().isEmpty()) {
            pending.add("verifier-review-without-checklist");
        }
        if (!pending.isEmpty()) return result(attempt, Status.PENDING_REVIEW, "MANUAL_REVIEW_REQUIRED", "Required judgments are outstanding", pending, reviewId, verificationPath);
        return result(attempt, Status.PASS, "ALL_REQUIRED_CHECKS_PASSED", "Execution and required checks completed", List.of(), reviewId, verificationPath);
    }

    public Result saveInitial(Path root, Attempt attempt, WorkerResult worker, VerificationResult verification) throws IOException {
        String verificationPath = verification == null ? null : "verification/verification.json";
        Result result = evaluate(attempt, worker, verification, verificationPath, null, null);
        if (Files.exists(root.resolve("initial-result.json"))) throw new IOException("Initial result already exists");
        files.write(root.resolve("initial-result.json"), result);
        files.write(root.resolve("result.json"), result);
        if (result.status() == Status.PENDING_REVIEW) {
            boolean executionReview = result.pendingChecks().contains("execution-classification");
            List<EvaluationReview.Check> checks = executionReview ? List.of() : attempt.definition().manualChecks().stream()
                    .map(check -> new EvaluationReview.Check(check.id(), null, check.instruction(), List.of())).toList();
            files.write(root.resolve("pending-review.json"), new EvaluationReview(result.attemptId(), result.caseId(), result.caseVersion(),
                    EvaluationEvidenceService.sha256(root.resolve("initial-result.json")), "", "", checks,
                    executionReview ? new EvaluationReview.ExecutionClassification(null, "Classify execution as FAIL or ERROR using evidence", List.of()) : null));
        }
        return result;
    }

    public Result review(Path attemptPath, Path inputPath) throws IOException {
        Path root = attemptPath.toRealPath();
        Attempt attempt = mapper.readValue(root.resolve("attempt.json").toFile(), Attempt.class);
        WorkerResult worker = mapper.readValue(root.resolve("execution.json").toFile(), WorkerResult.class);
        Result initial = mapper.readValue(root.resolve("initial-result.json").toFile(), Result.class);
        EvaluationReview input = mapper.readValue(inputPath.toFile(), EvaluationReview.class);
        if (!initial.attemptId().equals(input.attemptId()) || !initial.caseId().equals(input.caseId())
                || initial.caseVersion() != input.caseVersion()
                || !EvaluationEvidenceService.sha256(root.resolve("initial-result.json")).equals(input.initialResultSha256())) {
            throw new IOException("Review identity does not match this original attempt");
        }
        requireText(input.reviewer(), "reviewer");
        requireText(input.comment(), "comment");
        boolean executionReview = initial.reasonCode().equals("EXECUTION_UNCLASSIFIED");
        if (executionReview) {
            var classification = input.executionClassification();
            if (classification == null || classification.status() != Status.FAIL && classification.status() != Status.ERROR) {
                throw new IOException("Failed execution must be classified as FAIL or ERROR");
            }
            requireText(classification.reason(), "execution classification reason");
            validateEvidence(root, classification.evidence());
            if (!input.checks().isEmpty()) throw new IOException("Execution review does not replace business checks");
        } else {
            if (input.executionClassification() != null) throw new IOException("This execution does not require classification");
            Set<String> required = new HashSet<>();
            for (EvaluationCase.ManualCheck check : attempt.definition().manualChecks()) required.add(check.id());
            if (required.isEmpty()) throw new IOException("This attempt has no manual checklist");
            Set<String> received = new HashSet<>();
            for (EvaluationReview.Check check : input.checks()) {
                if (!received.add(check.id()) || !required.contains(check.id()) || check.status() != Status.PASS && check.status() != Status.FAIL) {
                    throw new IOException("Review requires unique declared checks with PASS or FAIL");
                }
                requireText(check.comment(), "check comment");
                validateEvidence(root, check.evidence());
            }
            if (!received.equals(required)) throw new IOException("Review must complete the entire required checklist");
        }
        VerificationResult verification = Files.exists(root.resolve("verification/verification.json"))
                ? mapper.readValue(root.resolve("verification/verification.json").toFile(), VerificationResult.class) : null;
        String reviewId = "review-" + UUID.randomUUID();
        files.write(root.resolve("reviews").resolve(reviewId + ".json"), new EvaluationReview.Stored(reviewId, Instant.now().toString(), input));
        Result result = evaluate(attempt, worker, verification, initial.verificationPath(), input, reviewId);
        files.write(root.resolve("result.json"), result);
        return result;
    }

    private void validateEvidence(Path root, List<String> references) throws IOException {
        if (references == null || references.isEmpty()) throw new IOException("Manual judgment requires evidence");
        for (String reference : references) {
            if (!Files.isRegularFile(CaseCatalogService.resolve(root, reference))) throw new IOException("Evidence must reference a file");
        }
    }

    private static void requireText(String text, String label) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("Missing " + label);
    }

    private Result result(Attempt attempt, Status status, String code, String detail, List<String> pending,
                          String reviewId, String verificationPath) {
        return new Result(attempt.prepared().attemptId(), attempt.definition().id(), attempt.definition().version(), status,
                code, detail, List.copyOf(pending), reviewId, verificationPath, Instant.now().toString());
    }
}

package com.zlzcode.codeagent.evaluation.model;

import java.util.List;

public record VerificationResult(
        Status status,
        List<Check> checks,
        List<String> evidence,
        String error) {

    public VerificationResult {
        checks = checks == null ? List.of() : List.copyOf(checks);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public enum Status { PASS, FAIL, ERROR, PENDING_REVIEW }

    public record Check(String id, boolean passed, String detail) {
    }
}

package com.zlzcode.codeagent.evaluation.model;

import java.util.List;

public record EvaluationCase(
        String id,
        int version,
        String category,
        String snapshot,
        String prompt,
        Verifier verifier,
        List<Step> steps,
        List<ApprovalRule> approvalRules,
        List<ManualCheck> manualChecks) {

    public EvaluationCase {
        steps = steps == null ? List.of() : List.copyOf(steps);
        approvalRules = approvalRules == null ? List.of() : List.copyOf(approvalRules);
        manualChecks = manualChecks == null ? List.of() : List.copyOf(manualChecks);
    }

    public EvaluationCase(String id, int version, String category, String snapshot, String prompt, Verifier verifier) {
        this(id, version, category, snapshot, prompt, verifier, List.of(), List.of(), List.of());
    }

    public record Step(String id, String session, String prompt) { }

    public record ApprovalRule(List<String> operations, List<String> include, List<String> exclude) {
        public ApprovalRule {
            operations = operations == null ? List.of() : List.copyOf(operations);
            include = include == null ? List.of() : List.copyOf(include);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }
    }

    public record ManualCheck(String id, String instruction) { }

    public record Verifier(List<String> command, String workingDirectory, long timeoutSeconds) {
        public Verifier {
            command = command == null ? List.of() : List.copyOf(command);
        }
    }
}

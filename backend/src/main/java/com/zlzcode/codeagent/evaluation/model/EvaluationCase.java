package com.zlzcode.codeagent.evaluation.model;

import java.util.List;

public record EvaluationCase(
        String id,
        int version,
        String category,
        String snapshot,
        String prompt,
        Verifier verifier) {

    public record Verifier(List<String> command, String workingDirectory, long timeoutSeconds) {
        public Verifier {
            command = command == null ? List.of() : List.copyOf(command);
        }
    }
}

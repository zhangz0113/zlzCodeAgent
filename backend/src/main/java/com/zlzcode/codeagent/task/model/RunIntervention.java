package com.zlzcode.codeagent.task.model;

import java.time.Instant;
import java.util.Objects;

public record RunIntervention(
        String interventionId,
        String content,
        Instant acceptedAt,
        Integer appliedAfterToolRound) {

    public RunIntervention {
        if (interventionId == null || interventionId.isBlank()) {
            throw new IllegalArgumentException("Run intervention ID cannot be blank");
        }
        if (content == null || content.isBlank() || !content.equals(content.trim())) {
            throw new IllegalArgumentException("Run intervention content must be non-blank and trimmed");
        }
        Objects.requireNonNull(acceptedAt, "Run intervention accepted time cannot be null");
        if (appliedAfterToolRound != null && appliedAfterToolRound < 0) {
            throw new IllegalArgumentException("Applied tool round cannot be negative");
        }
    }

    public RunIntervention appliedAfter(int toolRound) {
        return new RunIntervention(interventionId, content, acceptedAt, toolRound);
    }
}

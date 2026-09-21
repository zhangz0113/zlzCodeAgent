package com.zlzcode.codeagent.task.model;

import java.util.List;
import java.util.Objects;

public record TaskPlan(long revision, List<TaskStep> steps) {

    public TaskPlan {
        if (revision < 1) {
            throw new IllegalArgumentException("Task plan revision must be positive");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "Task plan steps cannot be null"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Task plan needs at least one step");
        }
    }

    public enum TaskStepStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    public record TaskStep(String content, TaskStepStatus status) {

        public TaskStep {
            if (content == null || content.isBlank() || !content.equals(content.trim())) {
                throw new IllegalArgumentException("Task step content must be non-blank and trimmed");
            }
            Objects.requireNonNull(status, "Task step status cannot be null");
        }
    }
}

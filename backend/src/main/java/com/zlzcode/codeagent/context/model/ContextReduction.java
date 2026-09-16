package com.zlzcode.codeagent.context.model;

import com.zlzcode.codeagent.context.model.RunContextPlan.MaterialKind;

public final class ContextReduction {

    private ContextReduction() {
    }

    public record ReductionScope(String sessionId, String runId) {

        public ReductionScope {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("Reduction Session ID cannot be blank");
            }
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("Reduction Run ID cannot be blank");
            }
        }
    }

    public record ToolResultMaterial(
            String toolName,
            String toolCallId,
            boolean success,
            String modelContent) {

        public ToolResultMaterial {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("Tool result name cannot be blank");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("Tool result call ID cannot be blank");
            }
            if (modelContent == null) {
                throw new IllegalArgumentException("Tool result content cannot be null");
            }
        }
    }

    public record PreparedToolResult(
            String modelContent,
            boolean reduced,
            String resultRef) {
    }

    public record ResultReference(String value) {

        public ResultReference {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Result reference cannot be blank");
            }
        }
    }

    public record ContentRange(int offset, int limit) {

        public ContentRange {
            if (offset < 0 || limit < 1) {
                throw new IllegalArgumentException("Result range must be non-negative and non-empty");
            }
        }
    }

    public record RetrievedContent(
            String resultRef,
            String content,
            int offset,
            int nextOffset,
            int totalChars,
            boolean complete) {
    }

    public record CapacityReport(
            int estimatedTokensBefore,
            int inputTokenBudget,
            int reservedOutputTokens,
            int estimatedTokensAfter) {
    }

    public record ReductionRecord(
            int messageIndex,
            MaterialKind materialKind,
            String source,
            ReductionReason reason,
            int charsBefore,
            int charsAfter,
            boolean retrievable) {
    }

    public enum ReductionReason {
        SESSION_TOOL_RESULT,
        CURRENT_RUN_TOOL_RESULT,
        SESSION_ASSISTANT
    }

}

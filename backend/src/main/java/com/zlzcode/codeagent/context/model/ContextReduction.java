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

    public record ToolArgumentsMaterial(
            String toolName,
            String toolCallId,
            String arguments) {

        public ToolArgumentsMaterial {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("Tool arguments name cannot be blank");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("Tool arguments call ID cannot be blank");
            }
            if (arguments == null) {
                throw new IllegalArgumentException("Tool arguments cannot be null");
            }
        }
    }

    public record PreparedToolArguments(
            String modelArguments,
            boolean reduced,
            String argumentsRef) {
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
            PayloadUnit unit,
            String content,
            int offset,
            int nextOffset,
            int total,
            boolean complete) {
    }

    public enum PayloadRole {
        ARGUMENTS,
        RESULT
    }

    public enum PayloadKind {
        READ_LINES,
        GREP_MATCHES,
        GLOB_PATHS,
        OVERVIEW_ENTRIES,
        TOOL_ARGUMENTS,
        GENERIC_TEXT
    }

    public enum PayloadUnit {
        LINE("line"),
        MATCH("match"),
        PATH("path"),
        ENTRY("entry"),
        CHARACTER("character");

        private final String value;

        PayloadUnit(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
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
        CURRENT_RUN_TOOL_RESULT
    }

}

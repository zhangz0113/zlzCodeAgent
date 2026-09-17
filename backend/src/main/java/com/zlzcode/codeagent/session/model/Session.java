package com.zlzcode.codeagent.session.model;

import java.time.Instant;
import java.util.List;

public record Session(
        String sessionId,
        String workspaceId,
        Instant createdAt,
        Instant updatedAt,
        List<Turn> turns) {

    public Session {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public enum TurnState {
        INCOMPLETE,
        COMPLETED
    }

    public record Turn(
            String runId,
            TurnState state,
            Instant createdAt,
            UserMessage user,
            AssistantMessage assistant) {
    }

    public record UserMessage(String content) {
    }

    public record AssistantMessage(String content, List<ToolRound> toolRounds) {

        public AssistantMessage {
            toolRounds = toolRounds == null ? List.of() : List.copyOf(toolRounds);
        }

        public List<ToolHistory> toolHistory() {
            return toolRounds.stream()
                    .flatMap(round -> round.calls().stream())
                    .filter(call -> call.result() != null)
                    .map(call -> new ToolHistory(
                            call.name(), call.arguments(), call.result().modelContent()))
                    .toList();
        }
    }

    public record ToolRound(String assistantContent, List<ToolCall> calls) {

        public ToolRound {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }
    }

    public record ToolCall(String name, String arguments, ToolResult result) {
    }

    public record ToolResult(
            boolean success,
            String modelContent,
            String presentation,
            String resultRef) {
    }

    public record ToolHistory(String name, String arguments, String result) {
    }
}

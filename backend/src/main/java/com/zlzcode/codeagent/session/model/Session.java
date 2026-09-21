package com.zlzcode.codeagent.session.model;

import com.zlzcode.codeagent.task.model.RunIntervention;
import com.zlzcode.codeagent.task.model.TaskPlan;

import java.time.Instant;
import java.util.List;

public record Session(
        String sessionId,
        String workspaceId,
        Instant createdAt,
        Instant updatedAt,
        HistoryCheckpoint historyCheckpoint,
        List<Turn> turns) {

    public Session {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public enum TurnState {
        INCOMPLETE,
        COMPLETED
    }

    public record HistoryCheckpoint(
            String summaryContent,
            String coveredThroughRunId) {
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

    public record AssistantMessage(
            String content,
            List<ToolRound> toolRounds,
            TaskPlan taskPlan,
            List<RunIntervention> interventions) {

        public AssistantMessage(String content, List<ToolRound> toolRounds) {
            this(content, toolRounds, null, List.of());
        }

        public AssistantMessage {
            toolRounds = toolRounds == null ? List.of() : List.copyOf(toolRounds);
            interventions = interventions == null ? List.of() : List.copyOf(interventions);
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

    public record ToolCall(
            String name,
            String arguments,
            String argumentsRef,
            ToolResult result) {
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

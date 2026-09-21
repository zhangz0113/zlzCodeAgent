package com.zlzcode.codeagent.session.dto;

import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.task.model.RunIntervention;
import com.zlzcode.codeagent.task.model.TaskPlan;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record SessionResponse(
        String sessionId,
        String workspaceId,
        Instant createdAt,
        Instant updatedAt,
        List<TurnResponse> turns) {

    public static SessionResponse from(Session session) {
        return new SessionResponse(
                session.sessionId(),
                session.workspaceId(),
                session.createdAt(),
                session.updatedAt(),
                session.turns().stream().map(TurnResponse::from).toList());
    }

    public record TurnResponse(
            String runId,
            String state,
            Instant createdAt,
            UserResponse user,
            AssistantResponse assistant) {

        private static TurnResponse from(Session.Turn turn) {
            return new TurnResponse(
                    turn.runId(),
                    turn.state().name().toLowerCase(Locale.ROOT),
                    turn.createdAt(),
                    new UserResponse(turn.user().content()),
                    turn.assistant() == null ? null : AssistantResponse.from(turn.assistant()));
        }
    }

    public record UserResponse(String content) {
    }

    public record AssistantResponse(
            String content,
            List<ToolRoundResponse> toolRounds,
            List<ToolHistoryResponse> toolHistory,
            TaskPlan taskPlan,
            List<RunIntervention> interventions) {

        private static AssistantResponse from(Session.AssistantMessage assistant) {
            return new AssistantResponse(
                    assistant.content(),
                    assistant.toolRounds().stream().map(ToolRoundResponse::from).toList(),
                    assistant.toolHistory().stream().map(ToolHistoryResponse::from).toList(),
                    assistant.taskPlan(),
                    assistant.interventions());
        }
    }

    public record ToolRoundResponse(String assistantContent, List<ToolCallResponse> calls) {

        private static ToolRoundResponse from(Session.ToolRound round) {
            return new ToolRoundResponse(
                    round.assistantContent(),
                    round.calls().stream().map(ToolCallResponse::from).toList());
        }
    }

    public record ToolCallResponse(
            String name,
            String arguments,
            String argumentsRef,
            ToolResultResponse result) {

        private static ToolCallResponse from(Session.ToolCall call) {
            return new ToolCallResponse(
                    call.name(),
                    call.arguments(),
                    call.argumentsRef(),
                    call.result() == null ? null : ToolResultResponse.from(call.result()));
        }
    }

    public record ToolResultResponse(
            boolean success,
            String modelContent,
            String presentation,
            String resultRef) {

        private static ToolResultResponse from(Session.ToolResult result) {
            return new ToolResultResponse(
                    result.success(),
                    result.modelContent(),
                    result.presentation(),
                    result.resultRef());
        }
    }

    public record ToolHistoryResponse(String name, String arguments, String result) {

        private static ToolHistoryResponse from(Session.ToolHistory history) {
            return new ToolHistoryResponse(history.name(), history.arguments(), history.result());
        }
    }
}

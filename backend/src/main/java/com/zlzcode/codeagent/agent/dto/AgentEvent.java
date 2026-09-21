package com.zlzcode.codeagent.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zlzcode.codeagent.memory.model.Memory;
import com.zlzcode.codeagent.task.model.TaskPlan;

import java.util.List;

public sealed interface AgentEvent permits AgentEvent.Status, AgentEvent.TextDelta,
        AgentEvent.ToolStarted, AgentEvent.ToolFinished, AgentEvent.Completed, AgentEvent.Error,
        AgentEvent.RunStarted, AgentEvent.AssistantStep, AgentEvent.MemoryChanged,
        AgentEvent.TaskPlanUpdated, AgentEvent.RunInterventionApplied {

    @JsonProperty("type")
    String type();

    record Status(String label) implements AgentEvent {
        @Override
        public String type() {
            return "status";
        }
    }

    record RunStarted(String runId) implements AgentEvent {
        @Override
        public String type() {
            return "run_started";
        }
    }

    record TextDelta(String delta) implements AgentEvent {
        @Override
        public String type() {
            return "text_delta";
        }
    }

    record AssistantStep(String content) implements AgentEvent {
        @Override
        public String type() {
            return "assistant_step";
        }
    }

    record ToolStarted(String id, String label, String detail) implements AgentEvent {
        @Override
        public String type() {
            return "tool_started";
        }
    }

    record ToolFinished(String id, String state, String detail) implements AgentEvent {
        @Override
        public String type() {
            return "tool_finished";
        }
    }

    record ToolHistory(String name, String arguments, String result) {
    }

    record MemoryChange(
            Memory.ChangeType changeType,
            String id,
            Memory.Scope scope,
            Memory.Kind kind,
            String subjectKey,
            String content) {

        public static MemoryChange from(Memory.Change change) {
            return new MemoryChange(
                    change.type(),
                    change.entry().id(),
                    change.entry().scope(),
                    change.entry().kind(),
                    change.entry().subjectKey(),
                    change.entry().content());
        }
    }

    record MemoryChanged(List<MemoryChange> changes) implements AgentEvent {

        public MemoryChanged {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        @Override
        public String type() {
            return "memory_changed";
        }
    }

    record TaskPlanUpdated(TaskPlan plan) implements AgentEvent {
        @Override
        public String type() {
            return "task_plan_updated";
        }
    }

    record RunInterventionApplied(String interventionId) implements AgentEvent {
        @Override
        public String type() {
            return "run_intervention_applied";
        }
    }

    record RunMetrics(
            int steps,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("inputTokens") Integer inputTokens,
            @JsonProperty("outputTokens") Integer outputTokens) {
    }

    record Completed(RunMetrics metrics, @JsonProperty("toolHistory") List<ToolHistory> toolHistory)
            implements AgentEvent {
        @Override
        public String type() {
            return "completed";
        }
    }

    record Error(String message, String code, boolean retryable) implements AgentEvent {
        @Override
        public String type() {
            return "error";
        }
    }
}

package com.zlzcode.codeagent.task.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class TaskPlanUpdateDefinition implements ToolDefinition {

    public static final String NAME = "task_plan_update";

    private static final Set<String> ROOT_FIELDS = Set.of("steps");
    private static final Set<String> STEP_FIELDS = Set.of("content", "status");
    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "steps", Map.of(
                            "type", "array",
                            "minItems", 1,
                            "maxItems", 20,
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "content", Map.of("type", "string", "minLength", 1, "maxLength", 500),
                                            "status", Map.of(
                                                    "type", "string",
                                                    "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED"))),
                                    "required", List.of("content", "status"),
                                    "additionalProperties", false))),
            "required", List.of("steps"),
            "additionalProperties", false);

    private final ObjectMapper objectMapper;

    public TaskPlanUpdateDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "更新任务计划";
    }

    @Override
    public String description() {
        return "Maintain the complete structured plan for the current multi-step Run. "
                + "Send the entire ordered step list every time; it replaces the previous plan. "
                + "Use PENDING, IN_PROGRESS, and COMPLETED, with at most one IN_PROGRESS step. "
                + "Update the plan after execution results or user guidance change the next work. "
                + "Skip this tool for trivial single-step work.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public Validation validate(String arguments) {
        try {
            JsonNode root = objectMapper.readTree(arguments);
            if (root == null || !root.isObject() || root.properties().stream()
                    .anyMatch(entry -> !ROOT_FIELDS.contains(entry.getKey()))) {
                return rejected();
            }
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 20) {
                return rejected();
            }
            for (JsonNode step : steps) {
                if (!step.isObject() || step.properties().stream()
                        .anyMatch(entry -> !STEP_FIELDS.contains(entry.getKey()))) {
                    return rejected();
                }
                JsonNode content = step.get("content");
                JsonNode status = step.get("status");
                if (content == null || !content.isTextual() || content.asText().isBlank()
                        || content.asText().length() > 500
                        || status == null || !status.isTextual()) {
                    return rejected();
                }
                try {
                    com.zlzcode.codeagent.task.model.TaskPlan.TaskStepStatus.valueOf(status.asText());
                } catch (IllegalArgumentException exception) {
                    return rejected();
                }
            }
            return Validation.accepted();
        } catch (Exception exception) {
            return rejected();
        }
    }

    private Validation rejected() {
        return Validation.rejected(
                "TASK_PLAN_ARGUMENTS_INVALID", "任务计划参数无效，未更新计划");
    }
}

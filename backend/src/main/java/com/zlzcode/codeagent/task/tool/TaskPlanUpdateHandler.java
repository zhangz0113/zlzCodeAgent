package com.zlzcode.codeagent.task.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.task.exception.TaskProgressException;
import com.zlzcode.codeagent.task.model.TaskPlan;
import com.zlzcode.codeagent.task.service.TaskProgressService;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ToolCompleted;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolExecutionResult;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Component
public final class TaskPlanUpdateHandler implements ToolHandler {

    private final ObjectMapper objectMapper;
    private final TaskProgressService taskProgressService;

    public TaskPlanUpdateHandler(
            ObjectMapper objectMapper,
            TaskProgressService taskProgressService) {
        this.objectMapper = objectMapper;
        this.taskProgressService = taskProgressService;
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.fromCallable(() -> new ToolCompleted(update(context, arguments)));
    }

    private ToolOutcome update(ToolExecutionContext context, String arguments) {
        try {
            TaskPlan plan = taskProgressService.replacePlan(
                    context.sessionId(), context.runId(), parseSteps(arguments));
            return new ToolOutcome(
                    true,
                    objectMapper.writeValueAsString(new TaskPlanResult(true, plan)),
                    "任务计划已更新至第 " + plan.revision() + " 版");
        } catch (IllegalArgumentException exception) {
            return ToolOutcome.failure(
                    objectMapper,
                    "TASK_PLAN_INVALID",
                    "任务计划无效，未更新计划",
                    "The task plan is invalid. Send one complete list with unique trimmed steps "
                            + "and at most one IN_PROGRESS step.");
        } catch (TaskProgressException exception) {
            return ToolOutcome.failure(
                    objectMapper,
                    exception.code(),
                    "当前 Run 无法更新任务计划",
                    "The current Run no longer accepts task-plan updates.");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码任务计划结果", exception);
        }
    }

    private List<TaskPlan.TaskStep> parseSteps(String arguments) {
        try {
            JsonNode root = objectMapper.readTree(arguments);
            List<TaskPlan.TaskStep> steps = new ArrayList<>();
            for (JsonNode step : root.get("steps")) {
                steps.add(new TaskPlan.TaskStep(
                        step.get("content").asText().trim(),
                        TaskPlan.TaskStepStatus.valueOf(step.get("status").asText())));
            }
            return steps;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Validated task-plan arguments could not be decoded", exception);
        }
    }

    private record TaskPlanResult(boolean ok, TaskPlan plan) {
    }
}

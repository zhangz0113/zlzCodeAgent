package com.zlzcode.codeagent.task.service;

import com.zlzcode.codeagent.task.exception.TaskProgressException;
import com.zlzcode.codeagent.task.model.RunIntervention;
import com.zlzcode.codeagent.task.model.TaskPlan;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class TaskProgressService {

    private static final int MAX_STEPS = 20;
    private static final int MAX_STEP_CONTENT_CHARS = 500;
    private static final int MAX_INTERVENTION_CHARS = 4_000;

    private final Map<String, ActiveTaskProgress> activeRuns = new ConcurrentHashMap<>();
    private final Clock clock;

    public TaskProgressService() {
        this(Clock.systemUTC());
    }

    TaskProgressService(Clock clock) {
        this.clock = clock;
    }

    public void openRun(String sessionId, String runId) {
        ActiveTaskProgress progress = new ActiveTaskProgress(sessionId, runId);
        if (activeRuns.putIfAbsent(runId, progress) != null) {
            throw new IllegalStateException("Task progress already exists for Run " + runId);
        }
    }

    public void closeRun(String sessionId, String runId) {
        ActiveTaskProgress current = activeRuns.get(runId);
        if (current != null && current.sessionId.equals(sessionId)) {
            activeRuns.remove(runId, current);
        }
    }

    /*
     * 背景：模型会通过多次工具调用调整计划，局部补丁容易遗留已经失效的步骤和状态。
     * 设计意图：每次接收完整列表并由服务生成单调 revision，让模型和 UI 始终观察同一快照。
     * 关键约束：TaskPlan 只是当前 Run 的行动意图；不能跨 Run 复用，也不能把 COMPLETED 当成工具成功事实。
     */
    public TaskPlan replacePlan(
            String sessionId,
            String runId,
            List<TaskPlan.TaskStep> requestedSteps) {
        List<TaskPlan.TaskStep> steps = validateSteps(requestedSteps);
        ActiveTaskProgress progress = requireActive(sessionId, runId);
        synchronized (progress) {
            long nextRevision = progress.plan == null ? 1 : progress.plan.revision() + 1;
            progress.plan = new TaskPlan(nextRevision, steps);
            return progress.plan;
        }
    }

    public TaskPlan currentPlan(String sessionId, String runId) {
        ActiveTaskProgress progress = requireActive(sessionId, runId);
        synchronized (progress) {
            return progress.plan;
        }
    }

    public RunIntervention submitIntervention(String sessionId, String runId, String content) {
        String normalized = normalizeIntervention(content);
        ActiveTaskProgress progress = requireActive(sessionId, runId);
        RunIntervention intervention = new RunIntervention(
                "intervention-" + UUID.randomUUID(), normalized, clock.instant(), null);
        synchronized (progress) {
            progress.pendingInterventions.add(intervention);
        }
        return intervention;
    }

    /*
     * 背景：用户可能在工具执行或文件审批等待期间补充要求，立即写入模型历史会拆开合法的工具调用批次。
     * 设计意图：只由 ReAct 的模型请求前边界按提交顺序一次性排空，并记录要求生效时已经完成的工具轮次。
     * 关键约束：调用方只能在没有悬空工具调用时排空；若在批次中间应用，会破坏 assistant tool_calls 与结果配对。
     */
    public List<RunIntervention> drainInterventions(
            String sessionId,
            String runId,
            int completedToolRounds) {
        ActiveTaskProgress progress = requireActive(sessionId, runId);
        synchronized (progress) {
            if (progress.pendingInterventions.isEmpty()) {
                return List.of();
            }
            List<RunIntervention> applied = progress.pendingInterventions.stream()
                    .map(intervention -> intervention.appliedAfter(completedToolRounds))
                    .toList();
            progress.pendingInterventions.clear();
            progress.appliedInterventions.addAll(applied);
            return applied;
        }
    }

    private ActiveTaskProgress requireActive(String sessionId, String runId) {
        ActiveTaskProgress progress = activeRuns.get(runId);
        if (progress == null) {
            throw TaskProgressException.runNotActive();
        }
        if (!progress.sessionId.equals(sessionId)) {
            throw TaskProgressException.runNotFound();
        }
        return progress;
    }

    private List<TaskPlan.TaskStep> validateSteps(List<TaskPlan.TaskStep> requestedSteps) {
        if (requestedSteps == null || requestedSteps.isEmpty() || requestedSteps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Task plan needs between 1 and " + MAX_STEPS + " steps");
        }
        List<TaskPlan.TaskStep> steps = new ArrayList<>(requestedSteps.size());
        Set<String> contents = new HashSet<>();
        int inProgress = 0;
        for (TaskPlan.TaskStep requested : requestedSteps) {
            if (requested == null || requested.content() == null || requested.status() == null) {
                throw new IllegalArgumentException("Task plan step is incomplete");
            }
            String content = requested.content().trim();
            if (content.isEmpty() || content.length() > MAX_STEP_CONTENT_CHARS) {
                throw new IllegalArgumentException("Task plan step content is invalid");
            }
            if (!contents.add(content)) {
                throw new IllegalArgumentException("Task plan contains duplicate steps");
            }
            if (requested.status() == TaskPlan.TaskStepStatus.IN_PROGRESS) {
                inProgress++;
            }
            steps.add(new TaskPlan.TaskStep(content, requested.status()));
        }
        if (inProgress > 1) {
            throw new IllegalArgumentException("Task plan allows at most one in-progress step");
        }
        return List.copyOf(steps);
    }

    private String normalizeIntervention(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Run intervention content cannot be null");
        }
        String normalized = content.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_INTERVENTION_CHARS) {
            throw new IllegalArgumentException("Run intervention content is invalid");
        }
        return normalized;
    }

    private static final class ActiveTaskProgress {
        private final String sessionId;
        private final String runId;
        private final List<RunIntervention> pendingInterventions = new ArrayList<>();
        private final List<RunIntervention> appliedInterventions = new ArrayList<>();
        private TaskPlan plan;

        private ActiveTaskProgress(String sessionId, String runId) {
            if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("Task progress identity cannot be blank");
            }
            this.sessionId = sessionId;
            this.runId = runId;
        }
    }
}

package com.zlzcode.codeagent.agent.context;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.context.model.RunContextPlan;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionScope;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.task.model.RunIntervention;
import com.zlzcode.codeagent.task.model.TaskPlan;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 ReAct 执行的临时状态聚合，不跨请求或 Session 复用。
 */
public final class ReActContext {

    private final RunExecution execution;
    private final AuthorizedWorkspace workspace;
    private final RunContextPlan requestPlan;
    private final long startedAtNanos;
    private final List<LlmMessage> messages;
    private final int currentRunMessageStart;
    private final Set<String> knownCallIds = new HashSet<>();
    private final Set<String> completedCallIds = new HashSet<>();
    private final List<String> outstandingCallIds = new ArrayList<>();
    private final Map<String, LlmToolCall> visibleCallsById = new HashMap<>();
    private final Map<String, String> argumentRefsByCallId = new HashMap<>();
    private final List<ToolExecutionRecord> toolExecutions = new ArrayList<>();
    private final List<RunIntervention> interventions = new ArrayList<>();
    private int modelSteps;
    private int completedToolRounds;
    private Integer inputTokens;
    private Integer outputTokens;
    private String finalAnswer;
    private TaskPlan taskPlan;

    public ReActContext(
            RunExecution execution,
            AuthorizedWorkspace workspace,
            RunContextPlan requestPlan,
            long startedAtNanos) {
        this.execution = Objects.requireNonNull(execution, "Run execution cannot be null");
        this.workspace = Objects.requireNonNull(workspace, "Authorized workspace cannot be null");
        this.requestPlan = Objects.requireNonNull(requestPlan, "Run context plan cannot be null");
        this.startedAtNanos = startedAtNanos;
        List<LlmMessage> initialMessages = requestPlan.initialMessages();
        if (initialMessages == null || initialMessages.isEmpty()) {
            throw new IllegalArgumentException("Agent run requires initial messages");
        }
        this.messages = new ArrayList<>(initialMessages.size());
        for (LlmMessage message : initialMessages) {
            appendInitial(message);
        }
        this.currentRunMessageStart = messages.size();
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial Agent history contains outstanding tool calls");
        }
    }

    public RunExecution execution() {
        return execution;
    }

    public AuthorizedWorkspace workspace() {
        return workspace;
    }

    public List<LlmMessage> messagesSnapshot() {
        return List.copyOf(messages);
    }

    /*
     * 背景：运行中的用户补充要求可能在模型调用或工具批次执行期间到达，只有批次完整结束后才能合法进入消息历史。
     * 设计意图：由 ReAct 的模型请求前边界提供已经标注位置的 Intervention，Context 同时更新模型消息和持久化语义记录。
     * 关键约束：不能在存在悬空 tool call 时追加用户消息，也不能改变 appliedAfterToolRound；否则当前请求与 Session 重放会观察到不同顺序。
     */
    public void appendInterventions(List<RunIntervention> incoming) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Cannot append user guidance with outstanding tool calls");
        }
        for (RunIntervention intervention : incoming == null ? List.<RunIntervention>of() : incoming) {
            Objects.requireNonNull(intervention, "Run intervention cannot be null");
            if (intervention.appliedAfterToolRound() == null
                    || intervention.appliedAfterToolRound() != completedToolRounds) {
                throw new IllegalStateException("Run intervention was applied at a different tool round");
            }
            messages.add(new LlmMessage.TextMessage(
                    LlmMessage.MessageRole.USER, intervention.content()));
            interventions.add(intervention);
        }
    }

    public void recordTaskPlan(TaskPlan plan) {
        taskPlan = Objects.requireNonNull(plan, "Task plan cannot be null");
    }

    public TaskPlan taskPlan() {
        return taskPlan;
    }

    public int completedToolRounds() {
        return completedToolRounds;
    }

    public void completeToolRound() {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Cannot complete a tool round with outstanding calls");
        }
        completedToolRounds++;
    }

    /*
     * 背景：工具执行会持续扩展当前 Run 的模型可见消息，而固定材料的来源映射必须保持不变。
     * 设计意图：由 ReActContext 提供当前唯一消息快照，再委托不可变计划形成带来源的完整草稿，
     * 真实请求和核查结果随后由 Context Reduction Module 从同一草稿生成。
     * 关键约束：不能绕过该入口直接在 ReActLoop 拼消息；否则固定材料、动态工具消息和容量处理
     * 可能使用不同快照，导致实际输入与核查记录分叉。
     */
    public RunContextPlan.RequestDraft modelRequestDraft(
            List<LlmRequest.ToolDeclaration> availableTools) {
        return requestPlan.draft(
                messagesSnapshot(),
                execution.options().model(),
                execution.options().reasoningEffort(),
                availableTools);
    }

    public ReductionScope reductionScope() {
        return new ReductionScope(execution.sessionId(), execution.runId());
    }

    /*
     * 背景：Agent 可能经过多轮模型请求，最终指标必须覆盖整个 Run，而不能只记录最后一轮。
     * 设计意图：在每轮结果收口后统一累积步骤和用量，流程方法只负责根据结果推进状态。
     * 关键约束：该方法必须在处理当前结果前调用一次，不能在工具执行或递归分支中重复计数。
     */
    public void recordModelTurn(LlmTurnResult result) {
        Objects.requireNonNull(result, "LLM turn result cannot be null");
        modelSteps++;
        inputTokens = addUsage(inputTokens, result.inputTokens());
        outputTokens = addUsage(outputTokens, result.outputTokens());
    }

    public void appendAssistantToolCalls(
            String content,
            String reasoningContent,
            List<LlmToolCall> toolCalls,
            Map<String, String> argumentRefs) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Previous tool calls are still outstanding");
        }
        LlmMessage.AssistantToolCallsMessage message = new LlmMessage.AssistantToolCallsMessage(
                content, reasoningContent, toolCalls);
        registerToolCalls(message.toolCalls());
        if (argumentRefs != null) {
            for (Map.Entry<String, String> entry : argumentRefs.entrySet()) {
                if (!visibleCallsById.containsKey(entry.getKey())
                        || entry.getValue() == null
                        || entry.getValue().isBlank()) {
                    throw new IllegalArgumentException("Tool argument reference does not match visible call");
                }
                argumentRefsByCallId.put(entry.getKey(), entry.getValue());
            }
        }
        messages.add(message);
    }

    /*
     * 背景：工具结果既要按原 call ID 回灌给下一轮模型，也要在 Run 成功时投影到 Session 和 SSE。
     * 设计意图：在 Context 内用 call ID 合并“原始执行调用”和“模型可见调用”，消息与 Session 保存预览，执行事实只补结果。
     * 关键约束：只有工具执行返回最终结果后才能调用，且结果必须严格匹配当前批次顺序；
     * 不能把原始大参数重新写回消息或 Session，否则预执行快照失去控制后续请求容量的意义。
     */
    public void recordToolExecution(
            LlmToolCall call,
            ToolOutcome outcome,
            String resultRef) {
        Objects.requireNonNull(call, "LLM tool call cannot be null");
        Objects.requireNonNull(outcome, "Tool outcome cannot be null");
        LlmToolCall visibleCall = visibleCallsById.get(call.id());
        if (visibleCall == null || !visibleCall.name().equals(call.name())) {
            throw new IllegalStateException("Tool execution does not match visible call");
        }
        appendToolResult(call.id(), outcome.modelContent());
        toolExecutions.add(new ToolExecutionRecord(
                call.id(),
                visibleCall.name(),
                visibleCall.arguments() == null ? "" : visibleCall.arguments(),
                argumentRefsByCallId.get(call.id()),
                outcome.modelContent(),
                outcome.ok(),
                outcome.presentation(),
                resultRef));
    }

    public void appendFinalAssistant(String content) {
        if (!outstandingCallIds.isEmpty()) {
            throw new IllegalStateException("Cannot append final text with outstanding tool calls");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Final assistant content cannot be blank");
        }
        finalAnswer = content;
        messages.add(new LlmMessage.TextMessage(LlmMessage.MessageRole.ASSISTANT, content));
    }

    public Completion completion() {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            throw new IllegalStateException("Run has no final answer");
        }
        long durationMs = Math.max(1L, java.time.Duration.ofNanos(
                System.nanoTime() - startedAtNanos).toMillis());
        List<Session.ToolHistory> sessionTools = toolExecutions.stream()
                .map(record -> new Session.ToolHistory(
                        record.name(), record.arguments(), record.modelResult()))
                .toList();
        List<AgentEvent.ToolHistory> eventTools = toolExecutions.stream()
                .map(record -> new AgentEvent.ToolHistory(
                        record.name(), record.arguments(), record.modelResult()))
                .toList();
        return new Completion(
                finalAnswer,
                sessionAssistantRecord(),
                sessionTools,
                eventTools,
                taskPlan,
                List.copyOf(interventions),
                new AgentEvent.RunMetrics(
                        modelSteps, durationMs, inputTokens, outputTokens));
    }

    /*
     * 背景：Session 需要保留模型可见的工具轮次，而不能继续把同批调用压扁成互不相关的历史项。
     * 设计意图：从 ReActContext 的唯一消息账本投影持久化记录，并用执行元数据补充成功状态、展示文本和结果引用；不建立第二套采集状态。
     * 关键约束：hidden reasoning 和供应商 call ID 只能用于当前 Run，不能写入 Session；否则持久化格式会绑定供应商协议并泄露内部推理内容。
     */
    public Session.AssistantMessage sessionAssistantRecord() {
        Map<String, ToolExecutionRecord> executions = new HashMap<>();
        for (ToolExecutionRecord executionRecord : toolExecutions) {
            executions.put(executionRecord.callId(), executionRecord);
        }

        List<Session.ToolRound> rounds = new ArrayList<>();
        for (int index = currentRunMessageStart; index < messages.size(); index++) {
            LlmMessage message = messages.get(index);
            if (!(message instanceof LlmMessage.AssistantToolCallsMessage assistant)) {
                continue;
            }
            List<Session.ToolCall> calls = assistant.toolCalls().stream()
                    .map(call -> toSessionToolCall(call, executions.get(call.id())))
                    .toList();
            rounds.add(new Session.ToolRound(assistant.content(), calls));
        }
        return new Session.AssistantMessage(
                finalAnswer, rounds, taskPlan, List.copyOf(interventions));
    }

    public boolean hasSessionActivity() {
        return finalAnswer != null || !toolExecutions.isEmpty()
                || taskPlan != null || !interventions.isEmpty()
                || messages.subList(currentRunMessageStart, messages.size()).stream()
                .anyMatch(LlmMessage.AssistantToolCallsMessage.class::isInstance);
    }

    private Session.ToolCall toSessionToolCall(
            LlmToolCall call,
            ToolExecutionRecord executionRecord) {
        Session.ToolResult result = executionRecord == null
                ? null
                : new Session.ToolResult(
                        executionRecord.success(),
                        executionRecord.modelResult(),
                        executionRecord.presentation(),
                        executionRecord.resultRef());
        return new Session.ToolCall(
                call.name(),
                call.arguments() == null ? "" : call.arguments(),
                executionRecord == null ? argumentRefsByCallId.get(call.id()) : executionRecord.argumentsRef(),
                result);
    }

    private void appendToolResult(String callId, String content) {
        LlmMessage.ToolResultMessage message = new LlmMessage.ToolResultMessage(callId, content);
        completeToolCall(message.toolCallId());
        messages.add(message);
    }

    private void appendInitial(LlmMessage message) {
        Objects.requireNonNull(message, "Agent history message cannot be null");
        if (message instanceof LlmMessage.AssistantToolCallsMessage assistant) {
            if (!outstandingCallIds.isEmpty()) {
                throw new IllegalArgumentException("Initial tool-call groups cannot overlap");
            }
            registerToolCalls(assistant.toolCalls());
        } else if (message instanceof LlmMessage.ToolResultMessage result) {
            completeToolCall(result.toolCallId());
        } else if (message instanceof LlmMessage.TextMessage text
                && text.role() == LlmMessage.MessageRole.ASSISTANT
                && !outstandingCallIds.isEmpty()) {
            throw new IllegalArgumentException("Initial assistant text precedes outstanding tool results");
        }
        messages.add(message);
    }

    private void registerToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Assistant tool-call message cannot be empty");
        }
        Set<String> newIds = new HashSet<>();
        for (LlmToolCall toolCall : toolCalls) {
            if (toolCall == null || toolCall.id() == null || toolCall.id().isBlank()) {
                throw new IllegalArgumentException("Tool call ID cannot be blank");
            }
            if (!newIds.add(toolCall.id()) || knownCallIds.contains(toolCall.id())) {
                throw new IllegalStateException("Tool call ID was already used");
            }
        }
        knownCallIds.addAll(newIds);
        for (LlmToolCall call : toolCalls) {
            visibleCallsById.put(call.id(), call);
        }
        outstandingCallIds.addAll(toolCalls.stream().map(LlmToolCall::id).toList());
    }

    private void completeToolCall(String callId) {
        if (completedCallIds.contains(callId)) {
            throw new IllegalStateException("Tool result was already appended");
        }
        if (!knownCallIds.contains(callId)) {
            throw new IllegalStateException("Tool result references an unknown call ID");
        }
        if (outstandingCallIds.isEmpty() || !outstandingCallIds.getFirst().equals(callId)) {
            throw new IllegalStateException("Tool results must follow model tool-call order");
        }
        outstandingCallIds.removeFirst();
        completedCallIds.add(callId);
    }

    private Integer addUsage(Integer current, Integer incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        long total = (long) current + incoming;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public record Completion(
            String finalAnswer,
            Session.AssistantMessage sessionRecord,
            List<Session.ToolHistory> sessionToolHistory,
            List<AgentEvent.ToolHistory> toolHistory,
            TaskPlan taskPlan,
            List<RunIntervention> interventions,
            AgentEvent.RunMetrics metrics) {

        public Completion {
            Objects.requireNonNull(sessionRecord, "Session record cannot be null");
            sessionToolHistory = List.copyOf(sessionToolHistory);
            toolHistory = List.copyOf(toolHistory);
            interventions = List.copyOf(interventions);
            Objects.requireNonNull(metrics, "Run metrics cannot be null");
        }
    }

    private record ToolExecutionRecord(
            String callId,
            String name,
            String arguments,
            String argumentsRef,
            String modelResult,
            boolean success,
            String presentation,
            String resultRef) {
    }
}

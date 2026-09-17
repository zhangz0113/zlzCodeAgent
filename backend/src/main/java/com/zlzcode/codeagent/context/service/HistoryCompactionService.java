package com.zlzcode.codeagent.context.service;

import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmStreamEvent;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.agent.stream.LlmTurnStreamProcessor;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.exception.ContextReductionException;
import com.zlzcode.codeagent.context.model.ContextReduction.CapacityReport;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionScope;
import com.zlzcode.codeagent.context.model.HistoryCompaction;
import com.zlzcode.codeagent.context.model.RunContextPlan;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.session.model.SessionRunSnapshot;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;

@Service
public final class HistoryCompactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryCompactionService.class);
    private static final List<String> REQUIRED_HEADINGS = List.of(
            "## 当前目标",
            "## 生效约束",
            "## 已确认决定",
            "## 已完成状态",
            "## 关键证据",
            "## 未决问题",
            "## 下一步");
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是 Agent 会话的历史检查点生成器。请把较早的已完成对话压缩为当前仍然有效的状态，
            只输出以下七个 Markdown 二级标题，顺序不得改变：
            ## 当前目标
            ## 生效约束
            ## 已确认决定
            ## 已完成状态
            ## 关键证据
            ## 未决问题
            ## 下一步

            区分已确认、仍在讨论、已否决和已被替代的内容。保留必要的路径、类名、函数名、命令、
            错误信息、数值和载荷引用，但不要复制完整工具结果或 diff。retained-tail 只用于修正旧状态，
            不得把仅存在于 retained-tail 的新工作写入检查点。不要调用工具，不要输出标题之外的前后文。
            """;

    private final ContextProperties properties;
    private final ModelRequestAssembler requestAssembler;
    private final ContextReductionModule contextReduction;
    private final OpenAiChatClient openAiChatClient;
    private final LlmTurnStreamProcessor turnStreamProcessor;
    private final SessionService sessionService;

    public HistoryCompactionService(
            ContextProperties properties,
            ModelRequestAssembler requestAssembler,
            ContextReductionModule contextReduction,
            OpenAiChatClient openAiChatClient,
            LlmTurnStreamProcessor turnStreamProcessor,
            SessionService sessionService) {
        this.properties = properties;
        this.requestAssembler = requestAssembler;
        this.contextReduction = contextReduction;
        this.openAiChatClient = openAiChatClient;
        this.turnStreamProcessor = turnStreamProcessor;
        this.sessionService = sessionService;
    }

    /*
     * 背景：Phase 1 只能缩减可取回的工具载荷，历史形状一旦进入 ReActContext 就必须保持追加式稳定。
     * 设计意图：在创建 ReActContext 前完成一次预检；只有真实 Phase 1 仍超限时才生成并保存一个滚动检查点。
     * 关键约束：候选摘要必须先通过结构、压缩收益和最终容量校验再持久化；若先保存或在 Run 中途替换，
     * 摘要失败会污染后续历史，当前工具协议前缀也会失去一致性。
     */
    public Mono<RunContextPlan> prepareRunPlan(
            RunExecution execution,
            AuthorizedWorkspace workspace,
            SessionRunSnapshot sessionRun,
            String systemPolicy,
            List<LlmRequest.ToolDeclaration> availableTools) {
        Objects.requireNonNull(execution, "Run execution cannot be null");
        Objects.requireNonNull(workspace, "Authorized workspace cannot be null");
        Objects.requireNonNull(sessionRun, "Session Run snapshot cannot be null");
        List<LlmRequest.ToolDeclaration> tools = availableTools == null
                ? List.of()
                : List.copyOf(availableTools);

        return Mono.defer(() -> {
            RunContextPlan initialPlan = requestAssembler.prepare(
                    workspace, sessionRun, systemPolicy);
            RunContextPlan.RequestDraft initialDraft = draft(initialPlan, execution, tools);
            CapacityReport phaseOne = contextReduction.measureAfterAllowedReduction(
                    reductionScope(execution), initialDraft);
            if (phaseOne.estimatedTokensAfter() <= phaseOne.inputTokenBudget()) {
                return Mono.just(initialPlan);
            }

            HistoryCompaction.Plan compaction = selectPlan(
                    execution, workspace, sessionRun, systemPolicy, tools, phaseOne);
            LlmRequest summaryRequest = summaryRequest(execution, compaction);
            return turnStreamProcessor.collect(openAiChatClient.chat(
                            execution.options().openai(), summaryRequest))
                    .map(result -> validateSummary(execution, compaction, result))
                    .flatMap(candidate -> Mono.fromCallable(() -> validatePersistAndRebuild(
                                    execution,
                                    workspace,
                                    sessionRun,
                                    systemPolicy,
                                    tools,
                                    compaction,
                                    candidate))
                            .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    /*
     * 背景：任意消息级裁剪会拆散 User、Assistant 和工具 round，固定保留一个 Turn 又不足以承接近期决策。
     * 设计意图：只压缩已有检查点之后的连续 Completed Turn 前缀，至少保留最近两个，并在 75% 目标内向前扩展尾部。
     * 关键约束：必须至少新增覆盖一个完整 Turn，且边界只能向后推进；否则会出现半个工具协议或把旧检查点覆盖范围倒退。
     */
    private HistoryCompaction.Plan selectPlan(
            RunExecution execution,
            AuthorizedWorkspace workspace,
            SessionRunSnapshot sessionRun,
            String systemPolicy,
            List<LlmRequest.ToolDeclaration> tools,
            CapacityReport phaseOne) {
        List<Session.Turn> completed = sessionRun.completedHistory();
        int firstUncovered = firstUncoveredTurn(completed, sessionRun.historyCheckpoint());
        List<Session.Turn> uncovered = completed.subList(firstUncovered, completed.size());
        if (uncovered.size() <= properties.minimumRetainedTurns()) {
            throw ContextReductionException.capacityExceeded();
        }

        int retainStart = uncovered.size() - properties.minimumRetainedTurns();
        int targetTokens = (int) Math.floor(
                contextReduction.hardInputBudget() * properties.compactionTargetRatio());
        while (retainStart > 1) {
            int expandedRetainStart = retainStart - 1;
            Session.Turn boundary = uncovered.get(expandedRetainStart - 1);
            int estimated = estimateMaximumCheckpointPlan(
                    execution,
                    workspace,
                    sessionRun,
                    systemPolicy,
                    tools,
                    boundary.runId());
            if (estimated > targetTokens) {
                break;
            }
            retainStart = expandedRetainStart;
        }

        return new HistoryCompaction.Plan(
                sessionRun.historyCheckpoint(),
                uncovered.subList(0, retainStart),
                uncovered.subList(retainStart, uncovered.size()),
                phaseOne.estimatedTokensBefore(),
                phaseOne.estimatedTokensAfter(),
                targetTokens);
    }

    private int estimateMaximumCheckpointPlan(
            RunExecution execution,
            AuthorizedWorkspace workspace,
            SessionRunSnapshot sessionRun,
            String systemPolicy,
            List<LlmRequest.ToolDeclaration> tools,
            String boundaryRunId) {
        String placeholder = "x".repeat(properties.summaryMaxOutputTokens() * 4);
        SessionRunSnapshot candidate = new SessionRunSnapshot(
                sessionRun.sessionId(),
                sessionRun.runId(),
                sessionRun.workspaceId(),
                sessionRun.prompt(),
                new Session.HistoryCheckpoint(placeholder, boundaryRunId),
                sessionRun.completedHistory());
        RunContextPlan plan = requestAssembler.prepare(workspace, candidate, systemPolicy);
        return contextReduction.estimateRequestTokens(draft(plan, execution, tools));
    }

    private LlmRequest summaryRequest(
            RunExecution execution,
            HistoryCompaction.Plan compaction) {
        String payload = "previous-checkpoint:\n"
                + renderCheckpoint(compaction.previousCheckpoint())
                + "\n\nturns-to-compact:\n"
                + renderTurns(compaction.turnsToCompact())
                + "\n\nretained-tail-for-reconciliation:\n"
                + renderTurns(compaction.retainedTail());
        return new LlmRequest(
                execution.options().model(),
                null,
                List.of(
                        new LlmMessage.TextMessage(
                                LlmMessage.MessageRole.SYSTEM, SUMMARY_SYSTEM_PROMPT),
                        new LlmMessage.TextMessage(LlmMessage.MessageRole.USER, payload)),
                List.of(),
                properties.summaryMaxOutputTokens());
    }

    private HistoryCompaction.Candidate validateSummary(
            RunExecution execution,
            HistoryCompaction.Plan compaction,
            LlmTurnResult result) {
        if (result.stopReason() != LlmStreamEvent.StopReason.STOP
                || result.hasToolCalls()
                || result.content() == null
                || result.content().isBlank()
                || !hasRequiredHeadings(result.content())) {
            throw ContextReductionException.summaryInvalid();
        }
        Session.Turn coveredThrough = compaction.turnsToCompact().getLast();
        Session.HistoryCheckpoint checkpoint = new Session.HistoryCheckpoint(
                result.content().trim(), coveredThrough.runId());

        String source = renderCheckpoint(compaction.previousCheckpoint())
                + "\n" + renderTurns(compaction.turnsToCompact());
        int sourceTokens = estimateText(execution.options().model(), source);
        int summaryTokens = estimateText(execution.options().model(), checkpoint.summaryContent());
        if (summaryTokens >= sourceTokens) {
            throw ContextReductionException.summaryInvalid();
        }
        return new HistoryCompaction.Candidate(
                checkpoint, result.inputTokens(), result.outputTokens());
    }

    private RunContextPlan validatePersistAndRebuild(
            RunExecution execution,
            AuthorizedWorkspace workspace,
            SessionRunSnapshot sessionRun,
            String systemPolicy,
            List<LlmRequest.ToolDeclaration> tools,
            HistoryCompaction.Plan compaction,
            HistoryCompaction.Candidate candidate) {
        SessionRunSnapshot candidateSnapshot = new SessionRunSnapshot(
                sessionRun.sessionId(),
                sessionRun.runId(),
                sessionRun.workspaceId(),
                sessionRun.prompt(),
                candidate.checkpoint(),
                sessionRun.completedHistory());
        RunContextPlan candidatePlan = requestAssembler.prepare(
                workspace, candidateSnapshot, systemPolicy);
        RunContextPlan.AssembledRequest assembled = contextReduction.reduceRequest(
                reductionScope(execution), draft(candidatePlan, execution, tools));
        int finalTokens = assembled.composition().capacity().estimatedTokensAfter();

        sessionService.saveHistoryCheckpoint(
                sessionRun.sessionId(), candidate.checkpoint());
        SessionRunSnapshot refreshed = sessionService.refreshRun(
                sessionRun.sessionId(), sessionRun.runId(), sessionRun.prompt());
        RunContextPlan finalPlan = requestAssembler.prepare(workspace, refreshed, systemPolicy);
        HistoryCompaction.Observation observation = new HistoryCompaction.Observation(
                compaction.beforeTokens(),
                compaction.afterPhaseOneTokens(),
                compaction.turnsToCompact().size(),
                compaction.retainedTail().size(),
                candidate.inputTokens(),
                candidate.outputTokens(),
                finalTokens);
        LOGGER.info(
                "History compaction completed: sessionId={}, runId={}, beforeTokens={}, "
                        + "afterPhaseOneTokens={}, compactedTurns={}, retainedTurns={}, summaryInputTokens={}, "
                        + "summaryOutputTokens={}, finalTokens={}",
                sessionRun.sessionId(),
                sessionRun.runId(),
                observation.beforeTokens(),
                observation.afterPhaseOneTokens(),
                observation.compactedTurns(),
                observation.retainedTurns(),
                observation.summaryInputTokens(),
                observation.summaryOutputTokens(),
                observation.finalTokens());
        return finalPlan;
    }

    private int firstUncoveredTurn(
            List<Session.Turn> completed,
            Session.HistoryCheckpoint checkpoint) {
        if (checkpoint == null) {
            return 0;
        }
        for (int index = 0; index < completed.size(); index++) {
            if (completed.get(index).runId().equals(checkpoint.coveredThroughRunId())) {
                return index + 1;
            }
        }
        throw ContextReductionException.summaryInvalid();
    }

    private int estimateText(String model, String content) {
        return contextReduction.estimateRequestTokens(new LlmRequest(
                model,
                null,
                List.of(new LlmMessage.TextMessage(LlmMessage.MessageRole.USER, content)),
                List.of()));
    }

    private boolean hasRequiredHeadings(String content) {
        int previous = -1;
        for (String heading : REQUIRED_HEADINGS) {
            int current = content.indexOf(heading, previous + 1);
            if (current < 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private String renderCheckpoint(Session.HistoryCheckpoint checkpoint) {
        return checkpoint == null ? "(none)" : checkpoint.summaryContent();
    }

    private String renderTurns(List<Session.Turn> turns) {
        if (turns.isEmpty()) {
            return "(none)";
        }
        StringBuilder content = new StringBuilder();
        for (Session.Turn turn : turns) {
            content.append("### Turn ").append(turn.runId()).append('\n')
                    .append("USER:\n").append(turn.user().content()).append('\n');
            for (Session.ToolRound round : turn.assistant().toolRounds()) {
                if (round.assistantContent() != null && !round.assistantContent().isBlank()) {
                    content.append("ASSISTANT_STEP:\n")
                            .append(round.assistantContent()).append('\n');
                }
                for (Session.ToolCall call : round.calls()) {
                    content.append("TOOL_CALL ").append(call.name()).append(":\n")
                            .append(call.arguments()).append('\n');
                    if (call.argumentsRef() != null) {
                        content.append("argumentsRef: ").append(call.argumentsRef()).append('\n');
                    }
                    Session.ToolResult result = call.result();
                    content.append("TOOL_RESULT success=").append(result.success()).append(":\n")
                            .append(result.modelContent()).append('\n');
                    if (result.resultRef() != null) {
                        content.append("resultRef: ").append(result.resultRef()).append('\n');
                    }
                }
            }
            content.append("ASSISTANT_FINAL:\n")
                    .append(turn.assistant().content()).append("\n\n");
        }
        return content.toString();
    }

    private RunContextPlan.RequestDraft draft(
            RunContextPlan plan,
            RunExecution execution,
            List<LlmRequest.ToolDeclaration> tools) {
        return plan.draft(
                plan.initialMessages(),
                execution.options().model(),
                execution.options().reasoningEffort(),
                tools);
    }

    private ReductionScope reductionScope(RunExecution execution) {
        return new ReductionScope(execution.sessionId(), execution.runId());
    }
}

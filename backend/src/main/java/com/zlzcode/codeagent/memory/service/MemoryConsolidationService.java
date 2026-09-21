package com.zlzcode.codeagent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmStreamEvent;
import com.zlzcode.codeagent.agent.model.LlmTurnResult;
import com.zlzcode.codeagent.agent.model.RunExecution;
import com.zlzcode.codeagent.agent.stream.LlmTurnStreamProcessor;
import com.zlzcode.codeagent.memory.config.MemoryProperties;
import com.zlzcode.codeagent.memory.model.Memory;
import com.zlzcode.codeagent.openai.client.OpenAiChatClient;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.session.model.SessionRunSnapshot;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public final class MemoryConsolidationService {

    private static final String SYSTEM_PROMPT = """
            你是长期记忆归并器。你只提取已确认、跨后续对话仍可复用的信息，输出严格 JSON，禁止工具调用。
            可记录：稳定偏好、关键事实、已确认决定、可复用状态。寒暄、临时细节、推测和未确认提案不记录。
            Assistant 提案只有在用户明确确认后才能成为 DECISION。新的明确用户陈述覆盖旧记忆。
            禁止记录密码、密钥、令牌、健康信息、财务信息或其他敏感数据。

            输出格式：
            {"operations":[{"type":"UPSERT|DELETE|NOOP","scope":"GLOBAL|WORKSPACE",
            "kind":"PREFERENCE|FACT|DECISION|STATUS","subjectKey":"稳定的英文逻辑键",
            "content":"简洁、独立可理解的记忆内容"}]}

            没有变化时输出 {"operations":[]}。DELETE 不需要 content。不要输出 Markdown 或解释。
            """;

    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;
    private final MemoryService memoryService;
    private final OpenAiChatClient openAiChatClient;
    private final LlmTurnStreamProcessor turnStreamProcessor;

    public MemoryConsolidationService(
            ObjectMapper objectMapper,
            MemoryProperties properties,
            MemoryService memoryService,
            OpenAiChatClient openAiChatClient,
            LlmTurnStreamProcessor turnStreamProcessor) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.memoryService = memoryService;
        this.openAiChatClient = openAiChatClient;
        this.turnStreamProcessor = turnStreamProcessor;
    }

    /*
     * 背景：长期记忆是 Completed Turn 的派生结果，不能拖垮或回滚已经成功的主 Run。
     * 设计意图：在 Turn 落盘后发起一次无工具、短超时的独立归并调用，只把通过确定性校验的操作交给 Store。
     * 关键约束：归并不得调用工具、自动重试或修改 Session 历史；调用方必须吞掉失败，
     * 否则记忆模型抖动会把正确完成的用户任务错误标记为失败。
     */
    public Mono<List<Memory.Change>> consolidate(
            RunExecution execution,
            SessionRunSnapshot sessionRun,
            Session.AssistantMessage currentAssistant) {
        String payload = renderInput(sessionRun, currentAssistant);
        LlmRequest request = new LlmRequest(
                execution.options().model(),
                null,
                List.of(
                        new LlmMessage.TextMessage(
                                LlmMessage.MessageRole.SYSTEM, SYSTEM_PROMPT),
                        new LlmMessage.TextMessage(
                                LlmMessage.MessageRole.USER, payload)),
                List.of(),
                properties.consolidationMaxOutputTokens());

        return turnStreamProcessor.collect(openAiChatClient.chat(
                        execution.options().openai(), request))
                .map(this::parseOperations)
                .flatMap(operations -> Mono.fromCallable(() -> memoryService.apply(
                                operations,
                                sessionRun.workspaceId(),
                                sessionRun.sessionId(),
                                sessionRun.runId()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .timeout(properties.consolidationTimeout());
    }

    private List<Memory.Operation> parseOperations(LlmTurnResult result) {
        if (result.stopReason() != LlmStreamEvent.StopReason.STOP
                || result.hasToolCalls()
                || result.content() == null
                || result.content().isBlank()) {
            throw new IllegalArgumentException("Memory consolidation response is invalid");
        }
        try {
            ConsolidationResponse response = objectMapper.readValue(
                    result.content().trim(), ConsolidationResponse.class);
            if (response.operations() == null
                    || response.operations().size() > properties.consolidationMaxOperations()) {
                throw new IllegalArgumentException("Memory consolidation operations are invalid");
            }
            List<Memory.Operation> operations = new ArrayList<>();
            for (ProposedOperation proposed : response.operations()) {
                Memory.Operation operation = toOperation(proposed);
                if (operation != null) {
                    operations.add(operation);
                }
            }
            return List.copyOf(operations);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Memory consolidation response is not JSON", exception);
        }
    }

    private Memory.Operation toOperation(ProposedOperation proposed) {
        if (proposed == null || proposed.type() == null) {
            return null;
        }
        try {
            Memory.OperationType type = Memory.OperationType.valueOf(
                    proposed.type().trim().toUpperCase(Locale.ROOT));
            if (type == Memory.OperationType.NOOP) {
                return new Memory.Operation(type, null, null, null, null);
            }
            if (proposed.scope() == null || proposed.kind() == null) {
                return null;
            }
            return new Memory.Operation(
                    type,
                    Memory.Scope.valueOf(proposed.scope().trim().toUpperCase(Locale.ROOT)),
                    Memory.Kind.valueOf(proposed.kind().trim().toUpperCase(Locale.ROOT)),
                    proposed.subjectKey(),
                    proposed.content());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String renderInput(
            SessionRunSnapshot sessionRun,
            Session.AssistantMessage currentAssistant) {
        StringBuilder content = new StringBuilder();
        content.append("applicable-existing-memories:\n");
        List<Memory.Entry> memories = memoryService.listApplicable(sessionRun.workspaceId());
        if (memories.isEmpty()) {
            content.append("(none)\n");
        } else {
            for (Memory.Entry memory : memories) {
                content.append("- [")
                        .append(memory.scope()).append('/')
                        .append(memory.kind()).append('/')
                        .append(memory.subjectKey()).append("] ")
                        .append(memory.content()).append('\n');
            }
        }

        content.append("\nhistory-checkpoint:\n");
        if (sessionRun.historyCheckpoint() == null) {
            content.append("(none)\n");
        } else {
            content.append(sessionRun.historyCheckpoint().summaryContent()).append('\n');
        }

        content.append("\nrecent-completed-turns:\n");
        List<Session.Turn> history = sessionRun.completedHistory();
        int start = Math.max(0, history.size() - 2);
        for (int index = start; index < history.size(); index++) {
            renderTurn(content, history.get(index).user().content(), history.get(index).assistant());
        }
        renderTurn(content, sessionRun.prompt(), currentAssistant);
        return content.toString();
    }

    private void renderTurn(
            StringBuilder content,
            String userContent,
            Session.AssistantMessage assistant) {
        content.append("USER:\n").append(userContent).append('\n');
        for (Session.ToolRound round : assistant.toolRounds()) {
            if (round.assistantContent() != null && !round.assistantContent().isBlank()) {
                content.append("ASSISTANT_STEP:\n")
                        .append(round.assistantContent()).append('\n');
            }
        }
        content.append("ASSISTANT_FINAL:\n")
                .append(assistant.content()).append("\n\n");
    }

    private record ConsolidationResponse(List<ProposedOperation> operations) {
    }

    private record ProposedOperation(
            String type,
            String scope,
            String kind,
            String subjectKey,
            String content) {
    }
}

package com.zlzcode.codeagent.context.model;

import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Run 内固定的模型请求组装计划。
 */
public final class RunContextPlan {

    private final String runId;
    private final List<ContextEntry> initialEntries;

    public RunContextPlan(String runId, List<ContextEntry> initialEntries) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Run ID cannot be blank");
        }
        this.runId = runId;
        this.initialEntries = List.copyOf(Objects.requireNonNull(
                initialEntries, "Initial context entries cannot be null"));
        if (this.initialEntries.isEmpty()) {
            throw new IllegalArgumentException("Run context plan requires initial messages");
        }
    }

    public List<LlmMessage> initialMessages() {
        return initialEntries.stream().map(ContextEntry::message).toList();
    }

    /*
     * 背景：同一 Run 的模型可见消息会随工具调用增长，但固定项目材料只能准备一次，
     * 同时调试证据必须准确描述真正发送的请求。
     * 设计意图：校验当前消息仍以初始快照为前缀，再从同一组 ContextEntry 同时产生
     * LlmRequest 与 RequestComposition，避免请求和核查逻辑各自重建一套顺序。
     * 关键约束：当前消息只能在初始快照之后追加；若允许替换、删减或错序，来源映射会
     * 指向错误正文，日志将无法证明模型实际看见了什么。
     */
    public AssembledRequest assemble(
            List<LlmMessage> currentMessages,
            String model,
            String reasoningEffort,
            List<LlmRequest.ToolDeclaration> availableTools) {
        List<LlmMessage> messages = List.copyOf(Objects.requireNonNull(
                currentMessages, "Current model messages cannot be null"));
        validateInitialPrefix(messages);

        List<ContextEntry> entries = new ArrayList<>(messages.size());
        entries.addAll(initialEntries);
        for (int index = initialEntries.size(); index < messages.size(); index++) {
            entries.add(currentRunEntry(messages.get(index), index - initialEntries.size()));
        }

        List<LlmRequest.ToolDeclaration> tools = availableTools == null
                ? List.of() : List.copyOf(availableTools);
        List<LlmMessage> requestMessages = entries.stream().map(ContextEntry::message).toList();
        LlmRequest request = new LlmRequest(model, reasoningEffort, requestMessages, tools);
        RequestComposition composition = new RequestComposition(
                runId,
                toMessageEntries(entries),
                tools.stream().map(LlmRequest.ToolDeclaration::name).toList());
        return new AssembledRequest(request, composition);
    }

    private void validateInitialPrefix(List<LlmMessage> messages) {
        if (messages.size() < initialEntries.size()) {
            throw new IllegalArgumentException("Current messages lost initial context entries");
        }
        for (int index = 0; index < initialEntries.size(); index++) {
            if (!initialEntries.get(index).message().equals(messages.get(index))) {
                throw new IllegalArgumentException(
                        "Current messages changed initial context entry at index " + index);
            }
        }
    }

    private ContextEntry currentRunEntry(LlmMessage message, int dynamicIndex) {
        Objects.requireNonNull(message, "Current Run message cannot be null");
        return new ContextEntry(
                message,
                MaterialKind.CURRENT_RUN_REACT,
                "run:" + runId + "/message:" + dynamicIndex,
                MaterialScope.RUN,
                relationIds(message));
    }

    private List<RequestComposition.MessageEntry> toMessageEntries(List<ContextEntry> entries) {
        List<RequestComposition.MessageEntry> result = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            ContextEntry entry = entries.get(index);
            result.add(new RequestComposition.MessageEntry(
                    index,
                    entry.kind(),
                    entry.source(),
                    entry.scope(),
                    messageType(entry.message()),
                    contentChars(entry.message()),
                    entry.relationIds()));
        }
        return List.copyOf(result);
    }

    private MessageType messageType(LlmMessage message) {
        if (message instanceof LlmMessage.TextMessage text) {
            return switch (text.role()) {
                case SYSTEM -> MessageType.SYSTEM;
                case USER -> MessageType.USER;
                case ASSISTANT -> MessageType.ASSISTANT;
            };
        }
        if (message instanceof LlmMessage.AssistantToolCallsMessage) {
            return MessageType.ASSISTANT_TOOL_CALLS;
        }
        return MessageType.TOOL_RESULT;
    }

    private int contentChars(LlmMessage message) {
        long chars;
        if (message instanceof LlmMessage.TextMessage text) {
            chars = text.content().length();
        } else if (message instanceof LlmMessage.AssistantToolCallsMessage assistant) {
            chars = length(assistant.content()) + length(assistant.hiddenReasoning());
            for (LlmToolCall call : assistant.toolCalls()) {
                chars += (long) call.id().length() + call.name().length() + length(call.arguments());
            }
        } else {
            LlmMessage.ToolResultMessage result = (LlmMessage.ToolResultMessage) message;
            chars = (long) result.toolCallId().length() + result.content().length();
        }
        return chars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) chars;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static List<String> relationIds(LlmMessage message) {
        if (message instanceof LlmMessage.AssistantToolCallsMessage assistant) {
            return assistant.toolCalls().stream().map(LlmToolCall::id).toList();
        }
        if (message instanceof LlmMessage.ToolResultMessage result) {
            return List.of(result.toolCallId());
        }
        return List.of();
    }

    public enum MaterialKind {
        SYSTEM_POLICY,
        PROJECT_INSTRUCTION,
        WORKSPACE_ENVIRONMENT,
        SESSION_HISTORY,
        CURRENT_USER,
        CURRENT_RUN_REACT
    }

    public enum MaterialScope {
        APPLICATION,
        WORKSPACE_ROOT,
        SESSION,
        RUN
    }

    public enum MessageType {
        SYSTEM,
        USER,
        ASSISTANT,
        ASSISTANT_TOOL_CALLS,
        TOOL_RESULT
    }

    public record ContextEntry(
            LlmMessage message,
            MaterialKind kind,
            String source,
            MaterialScope scope,
            List<String> relationIds) {

        public ContextEntry {
            Objects.requireNonNull(message, "Context entry message cannot be null");
            Objects.requireNonNull(kind, "Context entry kind cannot be null");
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("Context entry source cannot be blank");
            }
            Objects.requireNonNull(scope, "Context entry scope cannot be null");
            relationIds = relationIds == null ? List.of() : List.copyOf(relationIds);
        }
    }

    public record AssembledRequest(
            LlmRequest request,
            RequestComposition composition) {

        public AssembledRequest {
            Objects.requireNonNull(request, "Assembled LLM request cannot be null");
            Objects.requireNonNull(composition, "Request composition cannot be null");
        }
    }

    public record RequestComposition(
            String runId,
            List<MessageEntry> messages,
            List<String> availableTools) {

        public RequestComposition {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("Composition Run ID cannot be blank");
            }
            messages = List.copyOf(Objects.requireNonNull(
                    messages, "Composition messages cannot be null"));
            availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        }

        public record MessageEntry(
                int index,
                MaterialKind kind,
                String source,
                MaterialScope scope,
                MessageType messageType,
                int contentChars,
                List<String> relationIds) {

            public MessageEntry {
                if (index < 0) {
                    throw new IllegalArgumentException("Message composition index cannot be negative");
                }
                Objects.requireNonNull(kind, "Message composition kind cannot be null");
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("Message composition source cannot be blank");
                }
                Objects.requireNonNull(scope, "Message composition scope cannot be null");
                Objects.requireNonNull(messageType, "Message composition type cannot be null");
                if (contentChars < 0) {
                    throw new IllegalArgumentException("Message character count cannot be negative");
                }
                relationIds = relationIds == null ? List.of() : List.copyOf(relationIds);
            }
        }
    }
}

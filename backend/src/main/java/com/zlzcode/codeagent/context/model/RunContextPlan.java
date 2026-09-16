package com.zlzcode.codeagent.context.model;

import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.context.model.ContextReduction.CapacityReport;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Run 内固定的模型请求材料计划。
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
     * 背景：Phase 1 需要从完整消息账本派生容量内的请求视图，但 Phase 0 的来源映射
     * 仍必须准确描述当前 Run 真正积累的消息。
     * 设计意图：计划只形成带来源的完整 RequestDraft，缩减和最终请求生成统一交给
     * Context Reduction Module，避免原始账本与模型可见表示互相覆盖。
     * 关键约束：当前消息只能在初始快照之后追加；若允许替换、删减或错序，Module
     * 将无法证明裁剪前的来源和工具关联，协议核查也会失去依据。
     */
    public RequestDraft draft(
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
        return new RequestDraft(
                runId,
                model,
                reasoningEffort,
                entries,
                availableTools == null ? List.of() : availableTools);
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

        public ContextEntry withMessage(LlmMessage value) {
            return new ContextEntry(value, kind, source, scope, relationIds);
        }
    }

    public record RequestDraft(
            String runId,
            String model,
            String reasoningEffort,
            List<ContextEntry> entries,
            List<LlmRequest.ToolDeclaration> availableTools) {

        public RequestDraft {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("Request draft Run ID cannot be blank");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("Request draft model cannot be blank");
            }
            entries = List.copyOf(Objects.requireNonNull(entries, "Request draft entries cannot be null"));
            availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
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
            List<String> availableTools,
            CapacityReport capacity,
            List<ReductionRecord> reductions) {

        public RequestComposition {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("Composition Run ID cannot be blank");
            }
            messages = List.copyOf(Objects.requireNonNull(
                    messages, "Composition messages cannot be null"));
            availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
            Objects.requireNonNull(capacity, "Composition capacity cannot be null");
            reductions = reductions == null ? List.of() : List.copyOf(reductions);
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

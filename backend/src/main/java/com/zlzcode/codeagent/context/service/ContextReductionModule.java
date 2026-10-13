package com.zlzcode.codeagent.context.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.exception.ContextReductionException;
import com.zlzcode.codeagent.context.model.ContextReduction.CapacityReport;
import com.zlzcode.codeagent.context.model.ContextReduction.ContentRange;
import com.zlzcode.codeagent.context.model.ContextReduction.PayloadKind;
import com.zlzcode.codeagent.context.model.ContextReduction.PayloadRole;
import com.zlzcode.codeagent.context.model.ContextReduction.PreparedToolArguments;
import com.zlzcode.codeagent.context.model.ContextReduction.PreparedToolResult;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionReason;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionRecord;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionScope;
import com.zlzcode.codeagent.context.model.ContextReduction.ResultReference;
import com.zlzcode.codeagent.context.model.ContextReduction.RetrievedContent;
import com.zlzcode.codeagent.context.model.ContextReduction.ToolArgumentsMaterial;
import com.zlzcode.codeagent.context.model.ContextReduction.ToolResultMaterial;
import com.zlzcode.codeagent.context.model.RunContextPlan;
import com.zlzcode.codeagent.context.model.RunContextPlan.AssembledRequest;
import com.zlzcode.codeagent.context.model.RunContextPlan.ContextEntry;
import com.zlzcode.codeagent.context.model.RunContextPlan.MaterialKind;
import com.zlzcode.codeagent.context.model.RunContextPlan.MessageType;
import com.zlzcode.codeagent.context.model.RunContextPlan.RequestComposition;
import com.zlzcode.codeagent.context.model.RunContextPlan.RequestDraft;
import com.zlzcode.codeagent.context.store.FileContextResultStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public final class ContextReductionModule {

    private static final String RETRIEVAL_TOOL = "read_tool_result";

    private final ObjectMapper objectMapper;
    private final ContextProperties properties;
    private final FileContextResultStore resultStore;
    private final ToolPayloadCodec payloadCodec;

    public ContextReductionModule(
            ObjectMapper objectMapper,
            ContextProperties properties,
            FileContextResultStore resultStore) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resultStore = resultStore;
        this.payloadCodec = new ToolPayloadCodec(objectMapper);
    }

    /*
     * 背景：write/edit 的完整参数会直接进入执行或审批计划，Session 和后续模型请求却不能反复携带大正文。
     * 设计意图：任何工具调用先经过统一入口；达到阈值时先保存原始 JSON，再返回保持 JSON 合法的模型可见预览。
     * 关键约束：该方法必须在工具执行和创建审批计划之前成功完成，调用方仍须把原始参数交给 ToolRegistry；
     * 若用预览执行或先产生副作用再保存，MutationPlan 会丢内容，失败时也无法取回真实调用参数。
     */
    public PreparedToolArguments prepareToolArguments(
            ReductionScope scope,
            ToolArgumentsMaterial arguments) {
        Objects.requireNonNull(scope, "Reduction scope cannot be null");
        Objects.requireNonNull(arguments, "Tool arguments material cannot be null");
        if (codePointLength(arguments.arguments()) <= properties.spillThresholdChars()) {
            return new PreparedToolArguments(arguments.arguments(), false, null);
        }
        String reference = resultStore.save(
                scope,
                "tool-call:" + arguments.toolCallId() + "/arguments",
                arguments.toolName(),
                PayloadRole.ARGUMENTS,
                PayloadKind.TOOL_ARGUMENTS,
                arguments.arguments());
        String preview = payloadCodec.renderArgumentsPreview(
                arguments.arguments(), reference, properties.spillThresholdChars());
        return new PreparedToolArguments(preview, true, reference);
    }

    /*
     * 背景：工具服务已有自己的资源和语义限制，但旧的字符 fit 会在上下文层接手前
     * 不可逆地丢弃只读工具结果，后续引用无法恢复被截断部分。
     * 设计意图：在唯一工具记录点之前保存超大结果，并按工具语义只把有界预览交给 ReAct；
     * 普通结果保持原样，调用方不需要理解存储和预览格式。
     * 关键约束：必须先确认原文保存成功再返回引用，且 read_tool_result 本身不得再次
     * 进入 Spill；否则会出现承诺可取回但原文不存在，或取回结果无限外置的循环。
     */
    public PreparedToolResult prepareToolResult(
            ReductionScope scope,
            ToolResultMaterial result) {
        Objects.requireNonNull(scope, "Reduction scope cannot be null");
        Objects.requireNonNull(result, "Tool result material cannot be null");
        if (!result.success()
                || RETRIEVAL_TOOL.equals(result.toolName())
                || codePointLength(result.modelContent()) <= properties.spillThresholdChars()) {
            return new PreparedToolResult(result.modelContent(), false, null);
        }

        PayloadKind kind = payloadCodec.resultKind(result.toolName(), result.modelContent());
        String reference = resultStore.save(
                scope,
                "tool-call:" + result.toolCallId() + "/result",
                result.toolName(),
                PayloadRole.RESULT,
                kind,
                result.modelContent());
        String preview = payloadCodec.renderResultPreview(
                kind,
                result.modelContent(),
                reference,
                "SPILLED",
                properties.spillThresholdChars());
        return new PreparedToolResult(preview, true, reference);
    }

    /*
     * 背景：模型窗口约束作用于消息、工具声明和当前 Run 动态内容组成的完整请求，
     * 旧 History Builder 的局部字符上限既看不见完整请求，也会静默改变历史形状。
     * 设计意图：从不可变 RequestDraft 计算容量，只替换允许缩减的正文，并从同一最终
     * 条目快照同时生成 LlmRequest 与 RequestComposition。
     * 关键约束：不得删除或重排消息，不得修改用户内容、工具参数、call ID 或最新工具
     * 批次；全部候选处理后仍超限必须失败，不能恢复静默丢 Turn 的旧行为。
     */
    public AssembledRequest reduceRequest(
            ReductionScope scope,
            RequestDraft draft) {
        ReductionAttempt attempt = reduceAllowedPayloads(scope, draft);
        if (attempt.capacity().estimatedTokensAfter() > attempt.capacity().inputTokenBudget()) {
            throw ContextReductionException.capacityExceeded();
        }

        List<ContextEntry> finalEntries = attempt.entries();
        LlmRequest request = new LlmRequest(
                draft.model(),
                draft.reasoningEffort(),
                finalEntries.stream().map(ContextEntry::message).toList(),
                draft.availableTools());
        RequestComposition composition = new RequestComposition(
                draft.runId(),
                toMessageEntries(finalEntries),
                draft.availableTools().stream().map(LlmRequest.ToolDeclaration::name).toList(),
                attempt.capacity(),
                attempt.reductions());
        return new AssembledRequest(request, composition);
    }

    public CapacityReport measureAfterAllowedReduction(
            ReductionScope scope,
            RequestDraft draft) {
        /*
         * 背景：历史压缩只有在全部 Phase 1 候选耗尽后仍超预算时才允许触发。
         * 设计意图：复用正式请求的同一缩减过程并返回容量报告，不复制一套近似预检算法。
         * 关键约束：该入口不能放宽候选范围或吞掉存储失败；否则预检结论会与真正模型请求不一致。
         */
        return reduceAllowedPayloads(scope, draft).capacity();
    }

    private ReductionAttempt reduceAllowedPayloads(
            ReductionScope scope,
            RequestDraft draft) {
        Objects.requireNonNull(scope, "Reduction scope cannot be null");
        Objects.requireNonNull(draft, "Request draft cannot be null");
        if (!scope.runId().equals(draft.runId())) {
            throw new IllegalArgumentException("Reduction scope does not match request draft");
        }

        List<ContextEntry> visible = new ArrayList<>(draft.entries());
        List<ReductionRecord> reductions = new ArrayList<>();
        int budget = hardInputBudget();
        int before = estimateTokens(draft, visible);
        int after = before;

        if (after > budget) {
            int latestCurrentToolBatch = latestCurrentToolBatch(visible);

            for (int index : sessionToolResultCandidates(visible)) {
                ReductionRecord reduction = reduceToolResultEntry(
                        scope, visible, index, ReductionReason.SESSION_TOOL_RESULT);
                if (reduction != null) {
                    reductions.add(reduction);
                    after = estimateTokens(draft, visible);
                    if (after <= budget) break;
                }
            }

            if (after > budget) {
                for (int index : currentRunToolResultCandidates(visible, latestCurrentToolBatch)) {
                    ReductionRecord reduction = reduceToolResultEntry(
                            scope, visible, index, ReductionReason.CURRENT_RUN_TOOL_RESULT);
                    if (reduction != null) {
                        reductions.add(reduction);
                        after = estimateTokens(draft, visible);
                        if (after <= budget) break;
                    }
                }
            }

        }

        CapacityReport capacity = new CapacityReport(
                before,
                budget,
                properties.reservedOutputTokens(),
                after);
        return new ReductionAttempt(List.copyOf(visible), capacity, List.copyOf(reductions));
    }

    public RetrievedContent retrieveToolResult(
            String sessionId,
            ResultReference reference,
            ContentRange range) {
        Objects.requireNonNull(reference, "Result reference cannot be null");
        Objects.requireNonNull(range, "Content range cannot be null");
        if (range.limit() > properties.maxRetrievalChars()) {
            throw ContextReductionException.rangeInvalid();
        }
        FileContextResultStore.StoredResult stored = resultStore.read(sessionId, reference.value());
        try {
            ToolPayloadCodec.Page page = payloadCodec.retrieve(
                    stored.payloadKind(),
                    stored.content(),
                    range.offset(),
                    range.limit(),
                    properties.maxRetrievalChars());
            return new RetrievedContent(
                    reference.value(),
                    page.unit(),
                    page.content(),
                    page.offset(),
                    page.nextOffset(),
                    page.total(),
                    page.complete());
        } catch (IllegalArgumentException exception) {
            throw ContextReductionException.rangeInvalid();
        }
    }

    public int hardInputBudget() {
        return properties.contextWindowTokens() - properties.reservedOutputTokens();
    }

    public int estimateRequestTokens(RequestDraft draft) {
        Objects.requireNonNull(draft, "Request draft cannot be null");
        return estimateTokens(draft, draft.entries());
    }

    public int estimateRequestTokens(LlmRequest request) {
        Objects.requireNonNull(request, "LLM request cannot be null");
        return estimateSerializedTokens(request);
    }

    private ReductionRecord reduceToolResultEntry(
            ReductionScope scope,
            List<ContextEntry> visible,
            int index,
            ReductionReason reason) {
        ContextEntry entry = visible.get(index);
        LlmMessage.ToolResultMessage message = (LlmMessage.ToolResultMessage) entry.message();
        String existingReference = payloadCodec.previewReference(message.content());
        String reference;
        PayloadKind kind;
        String originalContent;
        if (existingReference == null) {
            String toolName = toolNameForResult(visible, index, message.toolCallId());
            kind = payloadCodec.resultKind(toolName, message.content());
            reference = resultStore.save(
                    scope,
                    entry.source(),
                    toolName,
                    PayloadRole.RESULT,
                    kind,
                    message.content());
            originalContent = message.content();
        } else {
            FileContextResultStore.StoredResult stored = resultStore.read(
                    scope.sessionId(), existingReference);
            reference = stored.reference();
            kind = stored.payloadKind();
            originalContent = stored.content();
        }

        String compact = payloadCodec.renderResultPreview(
                kind,
                originalContent,
                reference,
                "CAPACITY_PRUNED",
                properties.compactToolResultChars());
        int before = codePointLength(message.content());
        int after = codePointLength(compact);
        if (after >= before) {
            return null;
        }
        visible.set(index, entry.withMessage(
                new LlmMessage.ToolResultMessage(message.toolCallId(), compact)));
        return new ReductionRecord(
                index,
                entry.kind(),
                entry.source(),
                reason,
                before,
                after,
                true);
    }

    private List<Integer> sessionToolResultCandidates(List<ContextEntry> entries) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            ContextEntry entry = entries.get(index);
            if (entry.kind() == MaterialKind.SESSION_HISTORY
                    && entry.message() instanceof LlmMessage.ToolResultMessage) {
                result.add(index);
            }
        }
        return result;
    }

    private List<Integer> currentRunToolResultCandidates(
            List<ContextEntry> entries,
            int latestBatchStart) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            ContextEntry entry = entries.get(index);
            if (entry.kind() == MaterialKind.CURRENT_RUN_REACT
                    && entry.message() instanceof LlmMessage.ToolResultMessage
                    && index < latestBatchStart) {
                result.add(index);
            }
        }
        return result;
    }

    /*
     * 背景：当前 Run 最后一批工具调用可能仍在补齐结果，破坏它会让下一次模型请求失去刚发生的协议事实。
     * 设计意图：容量缩减只处理该批次之前、已经产生后续行为的旧工具结果，不裁剪最新批次。
     * 关键约束：返回值必须是最后一条 AssistantToolCalls 的起点；若保护范围前移或后移，可能留下悬空 call ID 或隐藏最新工具结果。
     */
    private int latestCurrentToolBatch(List<ContextEntry> entries) {
        int latest = Integer.MAX_VALUE;
        for (int index = 0; index < entries.size(); index++) {
            ContextEntry entry = entries.get(index);
            if (entry.kind() == MaterialKind.CURRENT_RUN_REACT
                    && entry.message() instanceof LlmMessage.AssistantToolCallsMessage) {
                latest = index;
            }
        }
        return latest;
    }

    private int estimateTokens(RequestDraft draft, List<ContextEntry> entries) {
        LlmRequest request = new LlmRequest(
                draft.model(),
                draft.reasoningEffort(),
                entries.stream().map(ContextEntry::message).toList(),
                draft.availableTools());
        return estimateSerializedTokens(request);
    }

    private int estimateSerializedTokens(LlmRequest request) {
        try {
            return estimateTextTokens(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to estimate model request capacity", exception);
        }
    }

    public static int estimateTextTokens(String text) {
        long ascii = 0;
        long nonAscii = 0;
        for (int codePoint : text.codePoints().toArray()) {
            if (codePoint <= 0x7F) ascii++;
            else nonAscii++;
        }
        long estimate = (ascii + 3L) / 4L + nonAscii;
        return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    private String toolNameForResult(
            List<ContextEntry> entries,
            int resultIndex,
            String toolCallId) {
        for (int index = resultIndex - 1; index >= 0; index--) {
            if (entries.get(index).message() instanceof LlmMessage.AssistantToolCallsMessage assistant) {
                for (LlmToolCall call : assistant.toolCalls()) {
                    if (toolCallId.equals(call.id())) return call.name();
                }
            }
        }
        return "unknown";
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
            chars = codePointLength(text.content());
        } else if (message instanceof LlmMessage.AssistantToolCallsMessage assistant) {
            chars = length(assistant.content()) + length(assistant.hiddenReasoning());
            for (LlmToolCall call : assistant.toolCalls()) {
                chars += (long) codePointLength(call.id())
                        + codePointLength(call.name())
                        + length(call.arguments());
            }
        } else {
            LlmMessage.ToolResultMessage result = (LlmMessage.ToolResultMessage) message;
            chars = (long) codePointLength(result.toolCallId()) + codePointLength(result.content());
        }
        return chars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) chars;
    }

    private int length(String value) {
        return value == null ? 0 : codePointLength(value);
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private record ReductionAttempt(
            List<ContextEntry> entries,
            CapacityReport capacity,
            List<ReductionRecord> reductions) {
    }

}

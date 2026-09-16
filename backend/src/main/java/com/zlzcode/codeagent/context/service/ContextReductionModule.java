package com.zlzcode.codeagent.context.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmRequest;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.exception.ContextReductionException;
import com.zlzcode.codeagent.context.model.ContextReduction.CapacityReport;
import com.zlzcode.codeagent.context.model.ContextReduction.ContentRange;
import com.zlzcode.codeagent.context.model.ContextReduction.PreparedToolResult;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionReason;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionRecord;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionScope;
import com.zlzcode.codeagent.context.model.ContextReduction.ResultReference;
import com.zlzcode.codeagent.context.model.ContextReduction.RetrievedContent;
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

    private static final String SPILL_TOOL = "read";
    private static final String RETRIEVAL_TOOL = "read_tool_result";

    private final ObjectMapper objectMapper;
    private final ContextProperties properties;
    private final FileContextResultStore resultStore;

    public ContextReductionModule(
            ObjectMapper objectMapper,
            ContextProperties properties,
            FileContextResultStore resultStore) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resultStore = resultStore;
    }

    /*
     * 背景：工具服务已有自己的资源和语义限制，但旧的字符 fit 会在上下文层接手前
     * 不可逆地丢弃 read 结果，后续引用无法恢复被截断部分。
     * 设计意图：在唯一工具记录点之前保存超大 read 结果，并只把有界预览交给 ReAct；
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
                || !SPILL_TOOL.equals(result.toolName())
                || RETRIEVAL_TOOL.equals(result.toolName())
                || codePointLength(result.modelContent()) <= properties.spillThresholdChars()) {
            return new PreparedToolResult(result.modelContent(), false, null);
        }

        String reference = resultStore.save(
                scope,
                "tool-call:" + result.toolCallId(),
                result.toolName(),
                result.modelContent());
        String preview = renderToolPreview(
                result.success(),
                "SPILLED",
                codePointLength(result.modelContent()),
                reference,
                result.modelContent(),
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
        Objects.requireNonNull(scope, "Reduction scope cannot be null");
        Objects.requireNonNull(draft, "Request draft cannot be null");
        if (!scope.runId().equals(draft.runId())) {
            throw new IllegalArgumentException("Reduction scope does not match request draft");
        }

        List<ContextEntry> visible = new ArrayList<>(draft.entries());
        List<ReductionRecord> reductions = new ArrayList<>();
        int budget = properties.contextWindowTokens() - properties.reservedOutputTokens();
        int before = estimateTokens(draft, visible);
        int after = before;

        if (after > budget) {
            int latestSessionAssistant = latestSessionAssistant(visible);
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

            if (after > budget) {
                for (int index : sessionAssistantCandidates(visible, latestSessionAssistant)) {
                    ReductionRecord reduction = reduceAssistantEntry(visible, index);
                    if (reduction != null) {
                        reductions.add(reduction);
                        after = estimateTokens(draft, visible);
                        if (after <= budget) break;
                    }
                }
            }
        }

        if (after > budget) {
            throw ContextReductionException.capacityExceeded();
        }

        List<ContextEntry> finalEntries = List.copyOf(visible);
        LlmRequest request = new LlmRequest(
                draft.model(),
                draft.reasoningEffort(),
                finalEntries.stream().map(ContextEntry::message).toList(),
                draft.availableTools());
        CapacityReport capacity = new CapacityReport(
                before,
                budget,
                properties.reservedOutputTokens(),
                after);
        RequestComposition composition = new RequestComposition(
                draft.runId(),
                toMessageEntries(finalEntries),
                draft.availableTools().stream().map(LlmRequest.ToolDeclaration::name).toList(),
                capacity,
                reductions);
        return new AssembledRequest(request, composition);
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
        String content = resultStore.read(sessionId, reference.value()).content();
        int[] points = content.codePoints().toArray();
        if (range.offset() > points.length) {
            throw ContextReductionException.rangeInvalid();
        }
        int end = Math.min(points.length, range.offset() + range.limit());
        String selected = new String(points, range.offset(), end - range.offset());
        return new RetrievedContent(
                reference.value(),
                selected,
                range.offset(),
                end,
                points.length,
                end >= points.length);
    }

    private ReductionRecord reduceToolResultEntry(
            ReductionScope scope,
            List<ContextEntry> visible,
            int index,
            ReductionReason reason) {
        ContextEntry entry = visible.get(index);
        LlmMessage.ToolResultMessage message = (LlmMessage.ToolResultMessage) entry.message();
        ExistingPreview existing = parsePreview(message.content());
        String reference;
        int originalChars;
        String previewSource;
        boolean success;
        if (existing == null) {
            reference = resultStore.save(
                    scope,
                    entry.source(),
                    "tool-result",
                    message.content());
            originalChars = codePointLength(message.content());
            previewSource = message.content();
            success = inferSuccess(message.content());
        } else {
            reference = existing.resultRef();
            originalChars = existing.originalChars();
            previewSource = existing.head() + "\n...\n" + existing.tail();
            success = existing.success();
        }

        String compact = renderToolPreview(
                success,
                "CAPACITY_PRUNED",
                originalChars,
                reference,
                previewSource,
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

    private ReductionRecord reduceAssistantEntry(List<ContextEntry> visible, int index) {
        ContextEntry entry = visible.get(index);
        LlmMessage.TextMessage message = (LlmMessage.TextMessage) entry.message();
        String compact = compactAssistant(message.content(), properties.compactAssistantChars());
        int before = codePointLength(message.content());
        int after = codePointLength(compact);
        if (after >= before) {
            return null;
        }
        visible.set(index, entry.withMessage(
                new LlmMessage.TextMessage(LlmMessage.MessageRole.ASSISTANT, compact)));
        return new ReductionRecord(
                index,
                entry.kind(),
                entry.source(),
                ReductionReason.SESSION_ASSISTANT,
                before,
                after,
                false);
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

    private List<Integer> sessionAssistantCandidates(
            List<ContextEntry> entries,
            int latestAssistant) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            ContextEntry entry = entries.get(index);
            if (index != latestAssistant
                    && entry.kind() == MaterialKind.SESSION_HISTORY
                    && entry.message() instanceof LlmMessage.TextMessage text
                    && text.role() == LlmMessage.MessageRole.ASSISTANT) {
                result.add(index);
            }
        }
        return result;
    }

    private int latestSessionAssistant(List<ContextEntry> entries) {
        int latest = -1;
        for (int index = 0; index < entries.size(); index++) {
            ContextEntry entry = entries.get(index);
            if (entry.kind() == MaterialKind.SESSION_HISTORY
                    && entry.message() instanceof LlmMessage.TextMessage text
                    && text.role() == LlmMessage.MessageRole.ASSISTANT) {
                latest = index;
            }
        }
        return latest;
    }

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
        try {
            String json = objectMapper.writeValueAsString(request);
            long ascii = 0;
            long nonAscii = 0;
            for (int codePoint : json.codePoints().toArray()) {
                if (codePoint <= 0x7F) ascii++;
                else nonAscii++;
            }
            long estimate = (ascii + 3L) / 4L + nonAscii;
            return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to estimate model request capacity", exception);
        }
    }

    private String renderToolPreview(
            boolean success,
            String state,
            int originalChars,
            String resultRef,
            String source,
            int maxChars) {
        int[] points = source.codePoints().toArray();
        int low = 0;
        int high = Math.min(points.length, maxChars);
        String best = encodePreview(new PreviewEnvelope(
                success, state, originalChars, resultRef, "", ""));
        while (low <= high) {
            int retained = (low + high) >>> 1;
            int headLength = retained * 3 / 4;
            int tailLength = retained - headLength;
            String head = new String(points, 0, Math.min(headLength, points.length));
            int tailStart = Math.max(0, points.length - tailLength);
            String tail = new String(points, tailStart, points.length - tailStart);
            String candidate = encodePreview(new PreviewEnvelope(
                    success, state, originalChars, resultRef, head, tail));
            if (codePointLength(candidate) <= maxChars) {
                best = candidate;
                low = retained + 1;
            } else {
                high = retained - 1;
            }
        }
        return best;
    }

    private String compactAssistant(String content, int maxChars) {
        int[] points = content.codePoints().toArray();
        if (points.length <= maxChars) return content;
        String prefix = "[Earlier assistant response shortened for context capacity. Original characters: "
                + points.length + "\n\n--- beginning ---\n";
        String middle = "\n\n--- ending ---\n";
        String suffix = "\n]";
        int available = Math.max(0, maxChars
                - codePointLength(prefix)
                - codePointLength(middle)
                - codePointLength(suffix));
        int headLength = available * 2 / 3;
        int tailLength = available - headLength;
        String head = new String(points, 0, Math.min(headLength, points.length));
        int tailStart = Math.max(0, points.length - tailLength);
        String tail = new String(points, tailStart, points.length - tailStart);
        return prefix + head + middle + tail + suffix;
    }

    private ExistingPreview parsePreview(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node == null
                    || !node.path("resultRef").isTextual()
                    || !node.path("originalChars").canConvertToInt()
                    || !node.path("head").isTextual()
                    || !node.path("tail").isTextual()) {
                return null;
            }
            return new ExistingPreview(
                    node.path("success").asBoolean(true),
                    node.path("originalChars").intValue(),
                    node.path("resultRef").asText(),
                    node.path("head").asText(),
                    node.path("tail").asText());
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private boolean inferSuccess(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            return node == null || !node.has("ok") || node.path("ok").asBoolean(true);
        } catch (JsonProcessingException exception) {
            return true;
        }
    }

    private String encodePreview(PreviewEnvelope preview) {
        try {
            return objectMapper.writeValueAsString(preview);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode context result preview", exception);
        }
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

    private record ExistingPreview(
            boolean success,
            int originalChars,
            String resultRef,
            String head,
            String tail) {
    }

    private record PreviewEnvelope(
            boolean success,
            String state,
            int originalChars,
            String resultRef,
            String head,
            String tail) {
    }
}

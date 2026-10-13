package com.zlzcode.codeagent.context.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlzcode.codeagent.context.model.ContextReduction.PayloadKind;
import com.zlzcode.codeagent.context.model.ContextReduction.PayloadUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具载荷的 JSON 结构识别、语义预览和分页编码器。
 */
final class ToolPayloadCodec {

    private static final String CONTEXT_FIELD = "_context";

    private final ObjectMapper objectMapper;

    ToolPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    PayloadKind resultKind(String toolName, String content) {
        PayloadKind candidate = switch (toolName) {
            case "read" -> PayloadKind.READ_LINES;
            case "grep" -> PayloadKind.GREP_MATCHES;
            case "glob" -> PayloadKind.GLOB_PATHS;
            case "workspace_overview" -> PayloadKind.OVERVIEW_ENTRIES;
            case "repository_search" -> PayloadKind.REPOSITORY_EVIDENCE;
            default -> PayloadKind.GENERIC_TEXT;
        };
        try {
            return semanticPayload(candidate, content) == null
                    ? PayloadKind.GENERIC_TEXT
                    : candidate;
        } catch (RuntimeException exception) {
            return PayloadKind.GENERIC_TEXT;
        }
    }

    String renderArgumentsPreview(
            String arguments,
            String reference,
            int maxChars) {
        try {
            JsonNode decoded = objectMapper.readTree(arguments);
            if (decoded == null || !decoded.isObject()) {
                return renderGenericPreview(arguments, reference, "SPILLED", maxChars);
            }
            for (int retained : List.of(512, 256, 64, 0)) {
                ObjectNode preview = compactObject((ObjectNode) decoded, retained, reference);
                String encoded = encode(preview);
                if (codePointLength(encoded) <= maxChars) {
                    return encoded;
                }
            }
            ObjectNode minimal = objectMapper.createObjectNode();
            minimal.set(CONTEXT_FIELD, contextNode(
                    "SPILLED", reference, codePointLength(arguments)));
            return encode(minimal);
        } catch (JsonProcessingException exception) {
            return renderGenericPreview(arguments, reference, "SPILLED", maxChars);
        }
    }

    String renderResultPreview(
            PayloadKind kind,
            String content,
            String reference,
            String state,
            int maxChars) {
        SemanticPayload payload = semanticPayload(kind, content);
        if (payload == null) {
            return renderGenericPreview(content, reference, state, maxChars);
        }
        int count = fittingItemCount(payload, 0, payload.total(), reference, state, maxChars);
        return encode(semanticEnvelope(payload, 0, count, reference, state));
    }

    Page retrieve(
            PayloadKind kind,
            String content,
            int offset,
            int limit,
            int maxChars) {
        SemanticPayload payload = semanticPayload(kind, content);
        if (payload == null) {
            int[] points = content.codePoints().toArray();
            if (offset > points.length) {
                throw new IllegalArgumentException("Payload offset is outside stored content");
            }
            int end = Math.min(points.length, offset + Math.min(limit, maxChars));
            return new Page(
                    PayloadUnit.CHARACTER,
                    new String(points, offset, end - offset),
                    offset,
                    end,
                    points.length,
                    end >= points.length);
        }
        if (offset > payload.total()) {
            throw new IllegalArgumentException("Payload offset is outside stored content");
        }
        int requested = Math.min(limit, payload.total() - offset);
        int count = fittingContentCount(payload, offset, requested, maxChars);
        if (kind == PayloadKind.REPOSITORY_EVIDENCE && requested > 0 && count == 0) {
            /*
             * 背景：历史证据可能遇到后来调小的取回预算，一整项已无法容纳。
             * 设计意图：明确报范围/预算不兼容，不返回看似成功却 nextOffset 不变的分页。
             * 关键约束：有剩余证据时每次取回必须推进；允许零进度会让 read_tool_result 无限循环。
             */
            throw new IllegalArgumentException("Evidence item exceeds current retrieval budget");
        }
        int nextOffset = offset + count;
        return new Page(
                payload.unit(),
                payload.pageContent(offset, count),
                offset,
                nextOffset,
                payload.total(),
                nextOffset >= payload.total());
    }

    String previewReference(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node != null && node.path("resultRef").isTextual()) {
                return node.path("resultRef").asText();
            }
            return null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private ObjectNode compactObject(ObjectNode original, int retained, String reference) {
        ObjectNode result = original.deepCopy();
        compactText(result, retained, reference);
        result.set(CONTEXT_FIELD, contextNode(
                "SPILLED", reference, codePointLength(encode(original))));
        return result;
    }

    private void compactText(JsonNode node, int retained, String reference) {
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value.isTextual() && codePointLength(value.asText()) > retained) {
                    ((ObjectNode) node).put(field, compactTextValue(value.asText(), retained, reference));
                } else {
                    compactText(value, retained, reference);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (value.isTextual() && codePointLength(value.asText()) > retained) {
                    array.set(index, objectMapper.getNodeFactory().textNode(
                            compactTextValue(value.asText(), retained, reference)));
                } else {
                    compactText(value, retained, reference);
                }
            }
        }
    }

    private String compactTextValue(String value, int retained, String reference) {
        int[] points = value.codePoints().toArray();
        int kept = Math.min(retained, points.length);
        int head = kept * 3 / 4;
        int tail = kept - head;
        String beginning = new String(points, 0, head);
        String ending = new String(points, points.length - tail, tail);
        return beginning + "[... full arguments: " + reference + " ...]" + ending;
    }

    private ObjectNode contextNode(String state, String reference, int originalChars) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("state", state);
        context.put("argumentsRef", reference);
        context.put("originalChars", originalChars);
        return context;
    }

    private String renderGenericPreview(
            String content,
            String reference,
            String state,
            int maxChars) {
        int[] points = content.codePoints().toArray();
        int low = 0;
        int high = Math.min(points.length, maxChars);
        String best = encode(genericEnvelope(state, reference, points.length, "", ""));
        while (low <= high) {
            int retained = (low + high) >>> 1;
            int headLength = retained * 3 / 4;
            int tailLength = retained - headLength;
            String head = new String(points, 0, Math.min(headLength, points.length));
            int tailStart = Math.max(0, points.length - tailLength);
            String tail = new String(points, tailStart, points.length - tailStart);
            String candidate = encode(genericEnvelope(
                    state, reference, points.length, head, tail));
            if (codePointLength(candidate) <= maxChars) {
                best = candidate;
                low = retained + 1;
            } else {
                high = retained - 1;
            }
        }
        return best;
    }

    private ObjectNode genericEnvelope(
            String state,
            String reference,
            int originalChars,
            String head,
            String tail) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("state", state);
        result.put("resultRef", reference);
        result.put("unit", PayloadUnit.CHARACTER.value());
        result.put("total", originalChars);
        result.put("head", head);
        result.put("tail", tail);
        return result;
    }

    private int fittingItemCount(
            SemanticPayload payload,
            int offset,
            int requested,
            String reference,
            String state,
            int maxChars) {
        int low = 0;
        int high = requested;
        int best = 0;
        while (low <= high) {
            int count = (low + high) >>> 1;
            String encoded = encode(semanticEnvelope(payload, offset, count, reference, state));
            if (codePointLength(encoded) <= maxChars) {
                best = count;
                low = count + 1;
            } else {
                high = count - 1;
            }
        }
        return best;
    }

    private int fittingContentCount(
            SemanticPayload payload,
            int offset,
            int requested,
            int maxChars) {
        int low = 0;
        int high = requested;
        int best = 0;
        while (low <= high) {
            int count = (low + high) >>> 1;
            String content = payload.pageContent(offset, count);
            if (codePointLength(content) <= maxChars) {
                best = count;
                low = count + 1;
            } else {
                high = count - 1;
            }
        }
        return best;
    }

    private ObjectNode semanticEnvelope(
            SemanticPayload payload,
            int offset,
            int count,
            String reference,
            String state) {
        int nextOffset = offset + count;
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", true);
        result.put("state", state);
        result.put("resultRef", reference);
        result.put("unit", payload.unit().value());
        result.put("offset", offset);
        result.put("nextOffset", nextOffset);
        result.put("total", payload.total());
        result.put("complete", nextOffset >= payload.total());
        result.set(payload.field(), payload.pageNode(offset, count));
        if (payload.unit() == PayloadUnit.EVIDENCE) result.setAll(payload.metadata);
        return result;
    }

    private SemanticPayload semanticPayload(PayloadKind kind, String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) return null;
            return switch (kind) {
                case READ_LINES -> readPayload(root);
                case GREP_MATCHES -> grepPayload(root);
                case GLOB_PATHS -> arrayPayload(
                        root.get("paths"), PayloadUnit.PATH, "paths");
                case OVERVIEW_ENTRIES -> arrayPayload(
                        root.get("entries"), PayloadUnit.ENTRY, "entries");
                case REPOSITORY_EVIDENCE -> evidencePayload(root);
                case TOOL_ARGUMENTS, GENERIC_TEXT -> null;
            };
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private SemanticPayload readPayload(JsonNode root) {
        JsonNode content = root.get("content");
        if (content == null || !content.isTextual()) return null;
        ArrayNode lines = objectMapper.createArrayNode();
        content.asText().lines().forEach(lines::add);
        return new SemanticPayload(PayloadUnit.LINE, "lines", lines, true);
    }

    private SemanticPayload evidencePayload(JsonNode root) {
        if (!root.path("ok").asBoolean() || !root.path("evidence").isArray()
                || !root.path("scope").isObject() || !root.path("limits").isObject()) return null;
        SemanticPayload payload = new SemanticPayload(PayloadUnit.EVIDENCE, "evidence", (ArrayNode) root.path("evidence"), false);
        payload.metadata.set("scope", root.path("scope"));
        payload.metadata.set("limits", root.path("limits"));
        return payload;
    }

    private SemanticPayload grepPayload(JsonNode root) {
        JsonNode files = root.get("files");
        if (files == null || !files.isArray()) return null;
        ArrayNode matches = objectMapper.createArrayNode();
        for (JsonNode file : files) {
            JsonNode path = file.get("path");
            JsonNode fileMatches = file.get("matches");
            if (path == null || !path.isTextual() || fileMatches == null || !fileMatches.isArray()) {
                return null;
            }
            for (JsonNode match : fileMatches) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("path", path.asText());
                item.set("lineNumber", match.get("lineNumber"));
                item.set("line", match.get("line"));
                matches.add(item);
            }
        }
        return new SemanticPayload(PayloadUnit.MATCH, "matches", matches, false);
    }

    private SemanticPayload arrayPayload(
            JsonNode items,
            PayloadUnit unit,
            String field) {
        if (items == null || !items.isArray()) return null;
        return new SemanticPayload(unit, field, (ArrayNode) items, false);
    }

    private String encode(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode tool payload preview", exception);
        }
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    record Page(
            PayloadUnit unit,
            String content,
            int offset,
            int nextOffset,
            int total,
            boolean complete) {
    }

    private final class SemanticPayload {

        private final PayloadUnit unit;
        private final String field;
        private final ArrayNode items;
        private final boolean joinLines;
        private final ObjectNode metadata = objectMapper.createObjectNode();

        private SemanticPayload(
                PayloadUnit unit,
                String field,
                ArrayNode items,
                boolean joinLines) {
            this.unit = unit;
            this.field = field;
            this.items = items;
            this.joinLines = joinLines;
        }

        private PayloadUnit unit() {
            return unit;
        }

        private String field() {
            return field;
        }

        private int total() {
            return items.size();
        }

        private JsonNode pageNode(int offset, int count) {
            ArrayNode selected = objectMapper.createArrayNode();
            for (int index = offset; index < offset + count; index++) {
                selected.add(items.get(index));
            }
            return selected;
        }

        private String pageContent(int offset, int count) {
            if (unit == PayloadUnit.EVIDENCE) {
                ObjectNode page = metadata.deepCopy();
                page.set(field, pageNode(offset, count));
                return encode(page);
            }
            if (!joinLines) return encode(pageNode(offset, count));
            List<String> lines = new ArrayList<>(count);
            for (int index = offset; index < offset + count; index++) {
                lines.add(items.get(index).asText());
            }
            return String.join("\n", lines);
        }
    }
}

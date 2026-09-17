package com.zlzcode.codeagent.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.context.config.ContextProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class ReadToolResultDefinition implements ToolDefinition {

    private static final String NAME = "read_tool_result";
    private static final Set<String> FIELDS = Set.of("result_ref", "offset", "limit");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("result_[a-f0-9]{40}");

    private final ObjectMapper objectMapper;
    private final int maxLimit;
    private final Map<String, Object> parametersSchema;

    public ReadToolResultDefinition(ObjectMapper objectMapper, ContextProperties properties) {
        this.objectMapper = objectMapper;
        this.maxLimit = properties.maxRetrievalChars();
        this.parametersSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "result_ref", Map.of("type", "string", "pattern", "^result_[a-f0-9]{40}$"),
                        "offset", Map.of("type", "integer", "minimum", 0),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", maxLimit)),
                "required", List.of("result_ref"),
                "additionalProperties", false);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "读取已保存的工具结果";
    }

    @Override
    public String description() {
        return "Read a bounded range from a stored tool payload. Offset and limit use the unit returned for that snapshot.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return parametersSchema;
    }

    @Override
    public Validation validate(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            if (node == null || !node.isObject() || hasUnknownField(node)) return rejected();
            JsonNode reference = node.get("result_ref");
            if (reference == null
                    || !reference.isTextual()
                    || !REFERENCE_PATTERN.matcher(reference.asText()).matches()) {
                return rejected();
            }
            if (!validNonNegativeInt(node.get("offset"))) return rejected();
            if (!validPositiveInt(node.get("limit"), maxLimit)) return rejected();
            return Validation.accepted();
        } catch (Exception exception) {
            return rejected();
        }
    }

    private boolean hasUnknownField(JsonNode node) {
        return node.fieldNames().hasNext() && !node.properties().stream()
                .allMatch(entry -> FIELDS.contains(entry.getKey()));
    }

    private boolean validNonNegativeInt(JsonNode node) {
        return node == null || node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() >= 0;
    }

    private boolean validPositiveInt(JsonNode node, int maximum) {
        return node == null || node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() >= 1
                && node.intValue() <= maximum;
    }

    private Validation rejected() {
        return Validation.rejected(
                "TOOL_ARGUMENTS_INVALID",
                "read_tool_result 参数无效，未读取结果");
    }
}

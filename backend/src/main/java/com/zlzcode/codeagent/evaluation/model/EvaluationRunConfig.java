package com.zlzcode.codeagent.evaluation.model;

import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public record EvaluationRunConfig(
        String agentJar, String agentRevision, String provider, String model, String baseUrl,
        String reasoningEffort, String apiKeyEnv, long runTimeoutSeconds, long modelTimeoutSeconds,
        Map<String, String> runtimeProperties) {

    private static final Set<String> ALLOWED_PROPERTIES = Set.of(
            "codeagent.context.context-window-tokens", "codeagent.context.reserved-output-tokens",
            "codeagent.context.spill-threshold-chars", "codeagent.context.compact-tool-result-chars",
            "codeagent.context.max-retrieval-chars", "codeagent.context.minimum-retained-turns",
            "codeagent.context.compaction-target-ratio", "codeagent.context.summary-max-output-tokens",
            "codeagent.memory.max-entries", "codeagent.memory.max-selected-entries",
            "codeagent.memory.selection-token-budget", "codeagent.memory.consolidation-max-operations",
            "codeagent.memory.consolidation-max-output-tokens", "codeagent.memory.consolidation-timeout",
            "codeagent.tool.read.default-limit", "codeagent.tool.read.max-limit",
            "codeagent.tool.read.max-line-length", "codeagent.tool.read.max-file-bytes",
            "codeagent.tool.glob.max-results", "codeagent.tool.glob.max-depth",
            "codeagent.tool.grep.max-matches", "codeagent.tool.grep.max-line-preview-bytes",
            "codeagent.tool.grep.max-file-bytes", "codeagent.tool.mutation.max-file-bytes",
            "codeagent.tool.mutation.approval-timeout", "codeagent.tool.mutation.max-content-chars",
            "codeagent.tool.mutation.max-preview-chars", "codeagent.tool.execution-timeout");

    public EvaluationRunConfig {
        runtimeProperties = runtimeProperties == null ? Map.of() : Map.copyOf(runtimeProperties);
        runTimeoutSeconds = runTimeoutSeconds == 0 ? 300 : runTimeoutSeconds;
        modelTimeoutSeconds = modelTimeoutSeconds == 0 ? 120 : modelTimeoutSeconds;
    }

    public EvaluationRunConfig resolve(Path configurationFile) {
        require(agentJar, "agentJar");
        require(agentRevision, "agentRevision (declared build revision)");
        require(provider, "provider");
        require(model, "model");
        require(apiKeyEnv, "apiKeyEnv");
        if (!apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid apiKeyEnv name");
        if (runTimeoutSeconds < 1 || modelTimeoutSeconds < 1) throw new IllegalArgumentException("Timeouts must be positive");
        for (String name : runtimeProperties.keySet()) {
            if (!ALLOWED_PROPERTIES.contains(name)) throw new IllegalArgumentException("Unsupported runtime property: " + name);
        }
        new OpenAiConnectionInput(baseUrl, "validation-only").normalizedBaseUri();
        Path jar = configurationFile.toAbsolutePath().getParent().resolve(agentJar).normalize();
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("agentJar does not exist");
        return new EvaluationRunConfig(jar.toString(), agentRevision, provider, model, baseUrl,
                reasoningEffort, apiKeyEnv, runTimeoutSeconds, modelTimeoutSeconds, runtimeProperties);
    }

    public OpenAiConnectionInput connection() {
        String key = System.getenv(apiKeyEnv);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Missing model credential environment variable: " + apiKeyEnv);
        return new OpenAiConnectionInput(baseUrl, key);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
    }
}

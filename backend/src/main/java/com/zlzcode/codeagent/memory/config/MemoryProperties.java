package com.zlzcode.codeagent.memory.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "codeagent.memory", ignoreUnknownFields = false)
public record MemoryProperties(
        @NotBlank @DefaultValue("../memories/memories.json") String stateFile,
        @Positive @DefaultValue("1000") int maxEntries,
        @Positive @DefaultValue("12") int maxSelectedEntries,
        @Positive @DefaultValue("1200") int selectionTokenBudget,
        @Positive @DefaultValue("8") int consolidationMaxOperations,
        @Positive @DefaultValue("1024") int consolidationMaxOutputTokens,
        @NotNull @DefaultValue("PT20S") Duration consolidationTimeout) {
}

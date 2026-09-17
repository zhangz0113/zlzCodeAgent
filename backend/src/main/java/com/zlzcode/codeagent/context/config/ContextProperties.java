package com.zlzcode.codeagent.context.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "codeagent.context", ignoreUnknownFields = false)
public record ContextProperties(
        @Positive @DefaultValue("32768") int contextWindowTokens,
        @Positive @DefaultValue("4096") int reservedOutputTokens,
        @Positive @DefaultValue("12000") int spillThresholdChars,
        @Positive @DefaultValue("2000") int compactToolResultChars,
        @Positive @DefaultValue("8000") int maxRetrievalChars,
        @Positive @DefaultValue("2") int minimumRetainedTurns,
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax(value = "1.0", inclusive = false)
        @DefaultValue("0.75") double compactionTargetRatio,
        @Positive @DefaultValue("2048") int summaryMaxOutputTokens,
        @NotBlank @DefaultValue("../runs/context-results") String resultDirectory) {

    @AssertTrue(message = "codeagent.context.reserved-output-tokens must be below context-window-tokens")
    public boolean isOutputReservationValid() {
        return reservedOutputTokens < contextWindowTokens;
    }

    @AssertTrue(message = "codeagent.context.compact-tool-result-chars must be below spill-threshold-chars")
    public boolean isCompactToolResultValid() {
        return compactToolResultChars < spillThresholdChars;
    }

    @AssertTrue(message = "codeagent.context.max-retrieval-chars must be below spill-threshold-chars")
    public boolean isRetrievalLimitValid() {
        return maxRetrievalChars < spillThresholdChars;
    }

    @AssertTrue(message = "codeagent.context.summary-max-output-tokens must be below context-window-tokens")
    public boolean isSummaryOutputLimitValid() {
        return summaryMaxOutputTokens < contextWindowTokens;
    }
}

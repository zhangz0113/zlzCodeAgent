package com.zlzcode.codeagent.retrieval.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "codeagent.retrieval", ignoreUnknownFields = false)
public record RetrievalProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("../workspaces/retrieval-index") String storageDir,
        @DefaultValue("") String qwenApiKey,
        @DefaultValue("") String qdrantUrl,
        @DefaultValue("") String qdrantApiKey,
        @DefaultValue("") String deepseekApiKey,
        @DefaultValue("https://maas.qianwenaiapi.com") String qwenUrl,
        @DefaultValue("qwen3.7-text-embedding-flash") String embeddingModel,
        @Positive @DefaultValue("1024") int dimensions,
        @Positive @DefaultValue("8") int embeddingBatchSize,
        @DefaultValue("https://api.deepseek.com") String deepseekUrl,
        @DefaultValue("deepseek-flash") String descriptionModel,
        @DefaultValue("deepseek-flash") String selectionModel,
        @DefaultValue("source-v1") String sourceTemplateVersion,
        @DefaultValue("description-v1") String descriptionTemplateVersion,
        @DefaultValue("selection-v1") String selectionTemplateVersion,
        @DefaultValue("structure-v2") String structureVersion,
        @DefaultValue("lexical-v1") String lexicalVersion,
        @DefaultValue("Retrieve repository source code and documentation relevant to the question.") String queryInstruction,
        @Positive @DefaultValue("1048576") int maxFileBytes,
        @Positive @DefaultValue("4800") int chunkChars,
        @Positive @DefaultValue("1200") int chunkTokens,
        @Positive @DefaultValue("400") int backgroundTokens,
        @Positive @DefaultValue("4") int descriptionBatchSize,
        @Positive @DefaultValue("8000") int descriptionInputTokens,
        @Positive @DefaultValue("2048") int descriptionOutputTokens,
        @Positive @DefaultValue("1024") int selectionOutputTokens,
        @Positive @DefaultValue("16000") int selectionInputTokens,
        @Positive @DefaultValue("11000") int primaryInputTokens,
        @Positive @DefaultValue("4000") int supplementaryInputTokens,
        @Positive @DefaultValue("20") int channelCandidates,
        @Positive @DefaultValue("12") int primaryCandidates,
        @Positive @DefaultValue("12") int supplementaryCandidates,
        @Positive @DefaultValue("12") int evidenceItems,
        @Positive @DefaultValue("6000") int evidenceTokens,
        @Positive @DefaultValue("10000") int evidenceChars,
        @Positive @DefaultValue("6000") int evidenceItemChars,
        @DefaultValue("PT120S") Duration searchTimeout,
        @DefaultValue("PT30M") Duration prepareTimeout,
        @DefaultValue("PT60S") Duration modelTimeout,
        @DefaultValue("PT30S") Duration qdrantTimeout) {

    @AssertTrue(message = "Enabled retrieval requires Qwen, DeepSeek and Qdrant configuration")
    public boolean isConnectionConfigured() {
        return !enabled || !qwenApiKey.isBlank() && !deepseekApiKey.isBlank() && !qdrantUrl.isBlank();
    }

    @AssertTrue(message = "Retrieval timeouts and batch sizes must fit the provider protocol")
    public boolean isBudgetValid() {
        return embeddingBatchSize <= 20 && !searchTimeout.isNegative() && !searchTimeout.isZero()
                && !prepareTimeout.isNegative() && !prepareTimeout.isZero()
                && !modelTimeout.isNegative() && !modelTimeout.isZero()
                && !qdrantTimeout.isNegative() && !qdrantTimeout.isZero();
    }

    @Override public String toString() {
        return "RetrievalProperties[enabled=" + enabled + ", credentials=<redacted>]";
    }
}

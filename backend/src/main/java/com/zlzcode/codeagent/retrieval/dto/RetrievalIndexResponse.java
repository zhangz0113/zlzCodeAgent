package com.zlzcode.codeagent.retrieval.dto;

import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.Error;
import java.time.Instant;

public record RetrievalIndexResponse(boolean enabled, State state, Operation operation, Stage stage,
        int processedFiles, Integer totalFiles, int indexedFiles, int skippedFiles, int chunkCount,
        Instant lastSuccessAt, Error error) {
    public static RetrievalIndexResponse from(Status status) {
        return new RetrievalIndexResponse(status.enabled(), status.state(), status.operation(), status.stage(),
                status.processedFiles(), status.totalFiles(), status.indexedFiles(), status.skippedFiles(),
                status.chunkCount(), status.lastSuccessAt(), status.error());
    }
}

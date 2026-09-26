package com.zlzcode.codeagent.evaluation.model;

import java.util.List;
import java.util.Map;

public record PreparedEvaluation(
        String attemptId,
        String preparedAt,
        String caseId,
        int caseVersion,
        String snapshotId,
        String workspace,
        ExperimentIdentity identity) {

    public record ExperimentIdentity(
            String batchId,
            String agentRevision,
            String model,
            String provider,
            String reasoningEffort,
            Map<String, String> strategy,
            Map<String, Long> budgets) {
    }

    public record SnapshotManifest(
            String snapshotId,
            String sourceDescription,
            List<SnapshotFile> files,
            List<ExcludedFile> excluded) {
        public SnapshotManifest {
            files = files == null ? List.of() : List.copyOf(files);
            excluded = excluded == null ? List.of() : List.copyOf(excluded);
        }
    }

    public record SnapshotFile(String path, String sha256) {
    }

    public record ExcludedFile(String path, String reason) {
    }
}

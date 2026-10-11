package com.zlzcode.codeagent.evaluation.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public record EvaluationReport(int schemaVersion, String id, String title, String generatedAt,
        String generator, List<Group> groups, List<Row> rows, Summary summary,
        Map<String, Summary> categories, Map<String, String> sourceHashes,
        List<String> dataIssues, List<EvaluationAnalysis.Card> issues, String metricScope) {
    public record Input(String id, String title, List<Selection> groups) { }
    public record Selection(String id, String label, String batch, String suite) { }
    public record Group(String id, String label, String batchId, String suitePath,
            EvaluationSuite suite, JsonNode config, String agentJarSha256, String casePackSha256) { }
    public record Step(String id, String session, String status, String runId, String answer,
            String errorCode, JsonNode metrics, String evidencePath) { }
    public record Row(String group, String caseId, int caseVersion, String casePath, String category,
            int attemptIndex, String attemptId, String status, String initialStatus, String reason,
            String detail, String snapshotId, String source, JsonNode conditions, List<Step> steps,
            JsonNode checks, List<String> pendingChecks, Map<String, String> evidence,
            Long runDurationMs, Long verificationDurationMs, Long totalDurationMs,
            List<String> missingEvidence) { }
    public record Metric(Long observedTotal, int recordedRuns, int totalRuns) { }
    public record Summary(int planned, int executed, Map<String, Integer> counts, int decided,
            Double passRate, Map<String, Metric> metrics, Long cumulativeDurationMs,
            int durationRecordedAttempts) { }
}

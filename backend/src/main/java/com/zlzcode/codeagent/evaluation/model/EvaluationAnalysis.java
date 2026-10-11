package com.zlzcode.codeagent.evaluation.model;

import java.util.List;
import java.util.Map;

public final class EvaluationAnalysis {
    private EvaluationAnalysis() { }
    public record Reference(String group, String attemptId, List<String> evidence) { }
    public record Entry(String id, String title, String status, String label, String author,
            String caseId, int caseVersion, List<Reference> attempts, String observed,
            String origin, String category, String confidence, String nextAction,
            String change, String retestReport, String comparisonReport, String resolution) { }
    public record Revision(int revision, String recordedAt, Entry entry) { }
    public record Card(String path, Revision current) { }
    public record Focus(String directory, Map<String, String> suites, String conditions) { }
}

package com.zlzcode.codeagent.evaluation.model;

import java.util.List;

public record EvaluationComparison(int schemaVersion, String title, String generatedAt, String generator,
        Experiment experiment, List<Change> changes, String baseline, String candidate) {
    public record Experiment(String title, String hypothesis, List<String> changedFactors,
            String environmentDeclaration, List<String> environmentEvidence) { }
    public record Change(String group, String caseId, int caseVersion, String conclusion,
            List<String> reasons, List<String> declaredDifferences,
            EvaluationReport.Summary baseline, EvaluationReport.Summary candidate,
            EvaluationReport.Summary baselinePassingCost, EvaluationReport.Summary candidatePassingCost) { }
}

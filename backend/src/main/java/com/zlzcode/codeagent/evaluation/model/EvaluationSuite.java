package com.zlzcode.codeagent.evaluation.model;

import java.util.List;

public record EvaluationSuite(String id, int version, List<CaseReference> cases) {
    public EvaluationSuite {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record CaseReference(String path, String id, int version) {
    }
}

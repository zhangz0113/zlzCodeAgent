package com.zlzcode.codeagent.task.dto;

import com.zlzcode.codeagent.task.model.RunIntervention;

import java.time.Instant;

public record RunInterventionResponse(
        String interventionId,
        String runId,
        String sessionId,
        String status,
        Instant acceptedAt) {

    public static RunInterventionResponse queued(
            String runId,
            String sessionId,
            RunIntervention intervention) {
        return new RunInterventionResponse(
                intervention.interventionId(), runId, sessionId, "QUEUED", intervention.acceptedAt());
    }
}

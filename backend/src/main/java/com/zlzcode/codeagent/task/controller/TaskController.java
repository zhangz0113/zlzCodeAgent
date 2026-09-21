package com.zlzcode.codeagent.task.controller;

import com.zlzcode.codeagent.task.dto.RunInterventionRequest;
import com.zlzcode.codeagent.task.dto.RunInterventionResponse;
import com.zlzcode.codeagent.task.model.RunIntervention;
import com.zlzcode.codeagent.task.service.TaskProgressService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/runs")
public final class TaskController {

    private final TaskProgressService taskProgressService;

    public TaskController(TaskProgressService taskProgressService) {
        this.taskProgressService = taskProgressService;
    }

    @PostMapping("/{runId}/interventions")
    public ResponseEntity<RunInterventionResponse> submitIntervention(
            @PathVariable String runId,
            @Valid @RequestBody RunInterventionRequest request) {
        RunIntervention intervention = taskProgressService.submitIntervention(
                request.sessionId(), runId, request.content());
        return ResponseEntity.accepted().body(RunInterventionResponse.queued(
                runId, request.sessionId(), intervention));
    }
}

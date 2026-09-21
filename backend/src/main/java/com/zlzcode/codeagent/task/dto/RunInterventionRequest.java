package com.zlzcode.codeagent.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RunInterventionRequest(
        @NotBlank String sessionId,
        @NotBlank @Size(max = 4_000) String content) {
}

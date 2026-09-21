package com.zlzcode.codeagent.memory.dto;

import com.zlzcode.codeagent.memory.model.Memory;

import java.time.Instant;

public record MemoryResponse(
        String id,
        Memory.Scope scope,
        String workspaceId,
        Memory.Kind kind,
        String subjectKey,
        String content,
        String sourceSessionId,
        String sourceRunId,
        Instant createdAt,
        Instant updatedAt) {

    public static MemoryResponse from(Memory.Entry entry) {
        return new MemoryResponse(
                entry.id(), entry.scope(), entry.workspaceId(), entry.kind(),
                entry.subjectKey(), entry.content(), entry.sourceSessionId(),
                entry.sourceRunId(), entry.createdAt(), entry.updatedAt());
    }
}

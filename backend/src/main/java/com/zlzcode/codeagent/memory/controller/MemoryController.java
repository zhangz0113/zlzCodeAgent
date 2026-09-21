package com.zlzcode.codeagent.memory.controller;

import com.zlzcode.codeagent.memory.dto.MemoryResponse;
import com.zlzcode.codeagent.memory.service.MemoryService;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memories")
public final class MemoryController {

    private final MemoryService memoryService;
    private final WorkspaceRegistry workspaceRegistry;

    public MemoryController(
            MemoryService memoryService,
            WorkspaceRegistry workspaceRegistry) {
        this.memoryService = memoryService;
        this.workspaceRegistry = workspaceRegistry;
    }

    @GetMapping
    public List<MemoryResponse> list(@RequestParam String workspaceId) {
        workspaceRegistry.resolve(workspaceId);
        return memoryService.listApplicable(workspaceId).stream()
                .map(MemoryResponse::from)
                .toList();
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(@PathVariable String memoryId) {
        memoryService.delete(memoryId);
        return ResponseEntity.noContent().build();
    }
}

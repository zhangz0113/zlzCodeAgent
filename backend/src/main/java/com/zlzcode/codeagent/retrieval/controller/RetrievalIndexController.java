package com.zlzcode.codeagent.retrieval.controller;

import com.zlzcode.codeagent.retrieval.dto.RetrievalIndexResponse;
import com.zlzcode.codeagent.retrieval.service.RepositoryIndexService;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/retrieval-index")
public final class RetrievalIndexController {
    private final WorkspaceRegistry workspaces;
    private final RepositoryIndexService index;
    public RetrievalIndexController(WorkspaceRegistry workspaces, RepositoryIndexService index) { this.workspaces = workspaces; this.index = index; }
    @GetMapping
    public Mono<RetrievalIndexResponse> status(@PathVariable String workspaceId) {
        return Mono.fromCallable(() -> RetrievalIndexResponse.from(index.status(workspaces.resolve(workspaceId))))
                .subscribeOn(index.scheduler());
    }
    @PostMapping("/prepare")
    public Mono<ResponseEntity<RetrievalIndexResponse>> prepare(@PathVariable String workspaceId) {
        return Mono.fromCallable(() -> ResponseEntity.accepted().body(RetrievalIndexResponse.from(index.prepare(workspaces.resolve(workspaceId)))))
                .subscribeOn(index.scheduler());
    }
}

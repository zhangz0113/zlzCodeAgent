package com.zlzcode.codeagent.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.retrieval.client.*;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.model.RepositorySearch.*;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.util.*;

@Service
public final class RepositorySearchService {
    private static final Logger LOG = LoggerFactory.getLogger(RepositorySearchService.class);
    private final RepositoryIndexService index;
    private final WorkspaceIndexCoordinator coordinator;
    private final RetrievalProperties properties;
    private final WorkspacePathGuard guard;
    private final EmbeddingClient embedding;
    private final QdrantIndexClient qdrant;
    private final RelationExpansionService relations;
    private final EvidenceModelClient model;
    private final EvidenceAssemblyService assembly;
    public RepositorySearchService(RepositoryIndexService index, WorkspaceIndexCoordinator coordinator, RetrievalProperties properties,
            WorkspacePathGuard guard, EmbeddingClient embedding, QdrantIndexClient qdrant, RelationExpansionService relations,
            EvidenceModelClient model, EvidenceAssemblyService assembly) {
        this.index = index; this.coordinator = coordinator; this.properties = properties; this.guard = guard;
        this.embedding = embedding; this.qdrant = qdrant; this.relations = relations; this.model = model; this.assembly = assembly;
    }
    public Mono<SearchDelivery> search(AuthorizedWorkspace workspace, Request request) {
        return Mono.defer(() -> {
            validate(request);
            if (!properties.enabled()) return Mono.error(new RetrievalException(RetrievalException.Code.DISABLED, "代码检索未启用"));
            var lease = coordinator.acquire(index.identity(workspace), Operation.SEARCH, properties.searchTimeout());
            /*
             * 背景：同步/解析是阻塞文件工作，外层订阅取消后底层覆盖仍可能正在退出。
             * 设计意图：在专用后台线程执行固定流水线，以 worker finally 释放执行权。
             * 关键约束：不把 scheduler 任务取消绑定到响应；过早释放会允许同工作区并发更新。
             */
            return Mono.<SearchDelivery>create(sink -> {
                try { index.scheduler().schedule(() -> {
                    try (lease) { sink.success(execute(workspace, request, lease)); }
                    catch (RuntimeException exception) { sink.error(exception); }
                }); } catch (RuntimeException exception) { lease.close(); sink.error(exception); }
            }).timeout(properties.searchTimeout(), Mono.error(new RetrievalException(RetrievalException.Code.TIMEOUT, "检索超时，请先准备索引再重试")));
        });
    }
    private SearchDelivery execute(AuthorizedWorkspace workspace, Request request, WorkspaceIndexCoordinator.Lease lease) {
        Scope scope = scope(workspace, request);
        long started = System.nanoTime();
        Snapshot snapshot = index.synchronize(workspace, lease, false);
        log("sync", started, snapshot.files().size());
        started = System.nanoTime();
        lease.stage(Stage.RETRIEVING);
        List<Double> vector = embedding.query(request.query(), lease); log("embedding", started, 1);
        started = System.nanoTime();
        JsonNode hits = qdrant.query(snapshot.manifest().collection(), vector, request.query(), scope, lease);
        Map<String, Chunk> current = new HashMap<>(); snapshot.files().values().forEach(f -> f.chunks().forEach(c -> current.put(c.id(), c)));
        List<Candidate> primary = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (JsonNode hit : hits) {
            String id = hit.path("id").asText(); Chunk chunk = current.get(id);
            if (chunk == null || !scope.includes(chunk) || !hit.path("payload").path("file_version").asText().equals(chunk.fileVersion())) throw RetrievalException.invalid();
            if (seen.add(id)) primary.add(new Candidate(chunk, List.of("matched"), List.of()));
        }
        log("qdrant", started, primary.size());
        started = System.nanoTime(); Expansion expansion = relations.expand(snapshot, primary, scope);
        if (!expansion.candidates().isEmpty()) {
            JsonNode supplementary = qdrant.retrieve(snapshot.manifest().collection(), expansion.candidates().stream().map(c -> c.chunk().id()).toList(), false, lease);
            Set<String> ids = new HashSet<>();
            for (JsonNode item : supplementary) {
                String id = item.path("id").asText(); Chunk chunk = current.get(id);
                if (chunk == null || !item.path("payload").path("file_version").asText().equals(chunk.fileVersion())) throw RetrievalException.invalid();
                ids.add(id);
            }
            if (ids.size() != expansion.candidates().size()) throw RetrievalException.invalid();
        }
        log("expansion", started, expansion.candidates().size());
        lease.stage(Stage.SELECTING); started = System.nanoTime();
        var selected = model.select(request.query(), expansion.primary(), expansion, lease); log("selection", started, selected.candidates().size());
        lease.stage(Stage.ASSEMBLING); started = System.nanoTime();
        SearchDelivery result = assembly.assemble(workspace, scope, selected.candidates(), selected.truncated(), lease);
        log("assembly", started, result.result().evidence().size()); lease.check(); return result;
    }
    private Scope scope(AuthorizedWorkspace workspace, Request request) {
        String path = request.path() == null ? "" : request.path();
        try { path = guard.relativePath(workspace.root(), guard.resolveExisting(workspace.root(), path)); }
        catch (IOException exception) { throw new IllegalArgumentException("检索路径不存在或不可访问"); }
        return new Scope(path, request.languages() == null ? List.of() : List.copyOf(new LinkedHashSet<>(request.languages())));
    }
    public static void validate(Request request) {
        if (request == null || request.query() == null || request.query().isBlank()
                || request.query().codePointCount(0, request.query().length()) > 2000)
            throw new IllegalArgumentException("检索问题必须为非空文本，最多 2000 字符");
        if (request.languages() != null && request.languages().stream().anyMatch(l -> l == null || !Set.of(
                "java", "javascript", "jsx", "typescript", "tsx", "markdown", "text", "json", "yaml", "toml", "xml", "properties").contains(l)))
            throw new IllegalArgumentException("检索语言范围无效");
    }
    private void log(String stage, long started, int count) { LOG.info("retrieval search stage={} elapsedMs={} count={}", stage, (System.nanoTime() - started) / 1_000_000, count); }
}

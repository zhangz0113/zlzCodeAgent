package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.retrieval.client.*;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.Error;
import com.zlzcode.codeagent.retrieval.store.FileIndexStore;
import com.zlzcode.codeagent.tool.service.FileSearchService;
import com.zlzcode.codeagent.tool.service.RunFileObservationService;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Scheduler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.zlzcode.codeagent.retrieval.service.WorkspaceIndexCoordinator.Lease;
import static com.zlzcode.codeagent.retrieval.exception.RetrievalException.Code.*;

@Service
public final class RepositoryIndexService {
    private static final Logger LOG = LoggerFactory.getLogger(RepositoryIndexService.class);
    private static final Set<String> EXCLUDED = Set.of("node_modules", "target", "dist", "build", ".idea", ".next", "coverage");
    private final RetrievalProperties properties;
    private final FileIndexStore store;
    private final WorkspaceIndexCoordinator coordinator;
    private final FileSearchService scanner;
    private final WorkspacePathGuard guard;
    private final RunFileObservationService hashes;
    private final SourceChunkService chunks;
    private final EmbeddingClient embedding;
    private final EvidenceModelClient model;
    private final QdrantIndexClient qdrant;
    private final Scheduler scheduler;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Error> errors = new ConcurrentHashMap<>();

    public RepositoryIndexService(RetrievalProperties properties, FileIndexStore store, WorkspaceIndexCoordinator coordinator,
            FileSearchService scanner, WorkspacePathGuard guard, RunFileObservationService hashes,
            SourceChunkService chunks, EmbeddingClient embedding, EvidenceModelClient model, QdrantIndexClient qdrant,
            @Qualifier("retrievalScheduler") Scheduler scheduler) {
        this.properties = properties; this.store = store; this.coordinator = coordinator; this.scanner = scanner;
        this.guard = guard; this.hashes = hashes; this.chunks = chunks; this.embedding = embedding;
        this.model = model; this.qdrant = qdrant; this.scheduler = scheduler;
    }
    public String identity(AuthorizedWorkspace workspace) { return store.identity(workspace); }
    public Scheduler scheduler() { return scheduler; }
    public Status prepare(AuthorizedWorkspace workspace) {
        enabled();
        Lease lease = coordinator.acquire(identity(workspace), Operation.PREPARE, properties.prepareTimeout());
        try {
            snapshot(workspace);
            errors.remove(identity(workspace));
            lease.stage(Stage.SCANNING);
            scheduler.schedule(() -> {
                try (lease) { synchronize(workspace, lease, true); }
                catch (RuntimeException exception) {
                    RetrievalException failure = safeFailure(exception);
                    errors.put(identity(workspace), new Error(failure.code(), failure.getMessage()));
                    LOG.warn("retrieval prepare failed workspace={} code={}", workspace.id(), failure.code());
                }
            });
            return status(workspace);
        } catch (RuntimeException exception) { lease.close(); throw exception; }
    }
    public Status status(AuthorizedWorkspace workspace) {
        Snapshot snapshot = snapshot(workspace);
        Manifest manifest = snapshot.manifest();
        Lease active = coordinator.active(identity(workspace));
        State state = manifest == null ? State.UNPREPARED : manifest.state();
        if (state == State.READY && !fingerprint().equals(manifest.fingerprint())) state = State.NEEDS_PREPARE;
        return new Status(properties.enabled(), state, active == null ? Operation.NONE : active.operation(),
                active == null ? Stage.IDLE : active.stage(), active == null ? 0 : active.processed(),
                active == null ? null : active.total(), snapshot.files().size(), manifest == null ? 0 : manifest.skipped().size(),
                snapshot.files().values().stream().mapToInt(f -> f.chunks().size()).sum(),
                manifest == null ? null : manifest.lastSuccessAt(), errors.get(identity(workspace)));
    }
    public Snapshot snapshot(AuthorizedWorkspace workspace) {
        return snapshots.computeIfAbsent(identity(workspace), key -> {
            Snapshot snapshot = store.load(workspace);
            Manifest manifest = snapshot.manifest();
            if (manifest != null && (manifest.pendingFile() != null || manifest.state() != State.READY))
                return new Snapshot(new Manifest(manifest.formatVersion(), manifest.workspaceId(), manifest.rootKey(),
                        manifest.collection(), manifest.files(), manifest.fingerprint(), State.NEEDS_PREPARE,
                        manifest.lastSuccessAt(), manifest.pendingFile(), manifest.skipped()), snapshot.files());
            return snapshot;
        });
    }
    public void requireReady(AuthorizedWorkspace workspace) {
        enabled();
        State state = status(workspace).state();
        if (state == State.UNPREPARED) throw new RetrievalException(NOT_READY, "索引尚未准备，请先准备索引");
        if (state != State.READY) throw new RetrievalException(NEEDS_PREPARE, "索引需要重新准备");
    }
    public Snapshot synchronize(AuthorizedWorkspace workspace, Lease lease, boolean preparing) {
        enabled();
        if (!preparing) requireReady(workspace);
        Snapshot initial = snapshot(workspace);
        Manifest old = initial.manifest();
        Fingerprint current = fingerprint();
        Map<String, FileRecord> files = new LinkedHashMap<>(initial.files());
        Map<String, String> skipped = new LinkedHashMap<>();
        String collection = store.collection(workspace);
        Instant lastSuccess = old == null ? null : old.lastSuccessAt();
        String pending = old == null ? null : old.pendingFile();
        Fingerprint previous = old == null ? null : old.fingerprint();
        try {
            lease.progress(Stage.SCANNING, 0, null);
            Map<String, ScannedFile> scanned = scan(workspace, skipped, lease);
            lease.progress(Stage.BUILDING, 0, scanned.size());
            boolean structureChanged = previous == null || !current.structure().equals(previous.structure());
            boolean embeddingChanged = previous == null || !current.embedding().equals(previous.embedding());
            boolean descriptionChanged = previous == null || !current.description().equals(previous.description());
            boolean lexicalChanged = previous == null || !current.lexical().equals(previous.lexical());
            boolean exists = qdrant.exists(collection, lease);
            if (exists && old == null) throw new RetrievalException(NEEDS_PREPARE, "同名集合没有本地归属记录，不能覆盖");
            boolean reset = previous != null && !current.collection().equals(previous.collection());
            if (reset && exists) qdrant.deleteCollection(collection, lease);
            if (!exists || reset) {
                persist(workspace, files, previous, State.NEEDS_PREPARE, lastSuccess, pending, skipped);
                qdrant.create(collection, lease);
                files.clear();
                previous = null; pending = null;
            }
            persist(workspace, files, previous, State.NEEDS_PREPARE, lastSuccess, pending, skipped);
            int processed = 0;
            for (ScannedFile source : scanned.values()) {
                lease.progress(Stage.BUILDING, processed, scanned.size());
                FileRecord existing = files.get(source.path());
                boolean replace = existing == null || !existing.version().equals(source.version())
                        || structureChanged || source.path().equals(pending);
                boolean update = replace || embeddingChanged || descriptionChanged || lexicalChanged;
                if (update) {
                    FileRecord record = replace ? chunks.build(identity(workspace), source.path(), source.version(), source.language(), source.bytes()) : existing;
                    if (replace || descriptionChanged) record = record.withChunks(model.describe(record.chunks(), lease));
                    List<Map<String, Object>> points = representations(record, replace || embeddingChanged,
                            replace || embeddingChanged || descriptionChanged, replace || lexicalChanged, lease);
                    /*
                     * 背景：Qdrant 和本地记录没有跨系统事务，替换失败后查询不能读取混合版本。
                     * 设计意图：先完成模型生成，再持久化待完成文件，然后等待远端删除/写入，最后发布本地记录。
                     * 关键约束：不能提前清除标记或记录同步成功；中断后必须按路径覆盖，避免旧 points 残留。
                     */
                    persist(workspace, files, previous, State.NEEDS_PREPARE, lastSuccess, source.path(), skipped);
                    lease.stage(Stage.SYNCING);
                    if (replace) { qdrant.deleteFile(collection, source.path(), lease); qdrant.upsert(collection, points, lease); }
                    else qdrant.updateRepresentations(collection, points, lease);
                    store.saveFile(workspace, record);
                    files.put(source.path(), record);
                    persist(workspace, files, previous, State.NEEDS_PREPARE, lastSuccess, null, skipped);
                }
                processed++;
                lease.progress(Stage.BUILDING, processed, scanned.size());
            }
            for (String path : List.copyOf(files.keySet())) if (!scanned.containsKey(path)) {
                persist(workspace, files, previous, State.NEEDS_PREPARE, lastSuccess, path, skipped);
                qdrant.deleteFile(collection, path, lease);
                files.remove(path);
                persist(workspace, files, previous, State.NEEDS_PREPARE, lastSuccess, null, skipped);
                store.removeFile(workspace, path);
            }
            lease.check();
            Snapshot result = persist(workspace, files, current, State.READY, Instant.now(), null, skipped);
            errors.remove(identity(workspace));
            LOG.info("retrieval sync workspace={} files={} chunks={} skipped={}", workspace.id(), files.size(),
                    files.values().stream().mapToInt(f -> f.chunks().size()).sum(), skipped.size());
            return result;
        } catch (RuntimeException exception) {
            RetrievalException failure = safeFailure(exception);
            errors.put(identity(workspace), new Error(failure.code(), failure.getMessage()));
            Snapshot latest = snapshot(workspace);
            Manifest manifest = latest.manifest();
            if (manifest != null) persist(workspace, latest.files(), manifest.fingerprint(), State.FAILED,
                    manifest.lastSuccessAt(), manifest.pendingFile(), manifest.skipped());
            throw failure;
        }
    }
    private Map<String, ScannedFile> scan(AuthorizedWorkspace workspace, Map<String, String> skipped, Lease lease) {
        Map<String, ScannedFile> result = new LinkedHashMap<>();
        try {
            var selection = scanner.files(workspace.root(), "", null, false, Integer.MAX_VALUE);
            if (selection.incomplete()) throw new IOException("incomplete scan");
            for (var file : selection.files()) {
                lease.check();
                String path = file.relativePath();
                if (Arrays.stream(path.split("/")).anyMatch(EXCLUDED::contains) || runtimePath(file.path())) {
                    skipped.put(path, "dependency/build/runtime"); continue;
                }
                String language = language(path);
                if (language == null) { skipped.put(path, "unsupported file type"); continue; }
                Path safe = guard.resolveExisting(workspace.root(), path);
                if (Files.size(safe) > properties.maxFileBytes()) { skipped.put(path, "file exceeds byte limit"); continue; }
                byte[] bytes = Files.readAllBytes(safe);
                if (bytes.length > properties.maxFileBytes()) { skipped.put(path, "file exceeds byte limit"); continue; }
                try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); }
                catch (CharacterCodingException exception) { skipped.put(path, "non UTF-8"); continue; }
                if (containsZero(bytes)) { skipped.put(path, "binary"); continue; }
                result.put(path, new ScannedFile(path, hashes.sha256(bytes), language, bytes));
            }
            return result;
        } catch (IOException exception) { throw new RetrievalException(SCAN_FAILED, "文件扫描不完整，未执行索引删除，请重新准备"); }
    }
    private boolean runtimePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(store.root())) return true;
        for (String dir : List.of("../runs", "../sessions", "../approvals", "../logs", "../memories"))
            if (absolute.startsWith(Path.of(dir).toAbsolutePath().normalize())) return true;
        return false;
    }
    private boolean containsZero(byte[] bytes) { for (byte value : bytes) if (value == 0) return true; return false; }
    public static String language(String path) {
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.'); String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "java" -> "java"; case "js", "mjs", "cjs" -> "javascript"; case "jsx" -> "jsx";
            case "ts", "mts", "cts" -> "typescript"; case "tsx" -> "tsx"; case "md", "markdown" -> "markdown";
            case "txt" -> "text"; case "json" -> "json"; case "yaml", "yml" -> "yaml";
            case "toml", "xml", "properties" -> extension;
            default -> Set.of("readme", "dockerfile", "makefile", "license").contains(name) ? "text" : null;
        };
    }
    private List<Map<String, Object>> representations(FileRecord file, boolean source, boolean description, boolean lexical, Lease lease) {
        List<Chunk> values = file.chunks();
        List<List<Double>> sourceVectors = source ? embedding.documents(values.stream().map(chunks::documentInput).toList(), lease) : List.of();
        List<Chunk> described = values.stream().filter(c -> c.description() != null).toList();
        List<List<Double>> descriptionVectors = description ? embedding.documents(described.stream().map(Chunk::description).toList(), lease) : List.of();
        Map<String, List<Double>> byId = new HashMap<>();
        for (int i = 0; i < descriptionVectors.size(); i++) byId.put(described.get(i).id(), descriptionVectors.get(i));
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Chunk chunk = values.get(i); Map<String, Object> vector = new LinkedHashMap<>();
            if (source) vector.put("source_dense", sourceVectors.get(i));
            if (byId.containsKey(chunk.id())) vector.put("description_dense", byId.get(chunk.id()));
            if (lexical) vector.put("lexical_sparse", QdrantIndexClient.lexical(chunk.path() + "\n" + chunk.name() + "\n" + chunk.source()));
            points.add(Map.of("id", chunk.id(), "vector", vector, "payload", QdrantIndexClient.payload(chunk)));
        }
        return points;
    }
    private Snapshot persist(AuthorizedWorkspace workspace, Map<String, FileRecord> files, Fingerprint fingerprint,
                             State state, Instant success, String pending, Map<String, String> skipped) {
        Manifest manifest = new Manifest(1, workspace.id(), guard.canonicalKey(workspace.root()), store.collection(workspace),
                List.copyOf(files.keySet()), fingerprint, state, success, pending, Map.copyOf(skipped));
        store.saveManifest(workspace, manifest);
        Snapshot snapshot = new Snapshot(manifest, Collections.unmodifiableMap(new LinkedHashMap<>(files)));
        snapshots.put(identity(workspace), snapshot); return snapshot;
    }
    public Fingerprint fingerprint() {
        return new Fingerprint(hash(properties.structureVersion() + ":" + properties.maxFileBytes() + ":" + properties.chunkChars()
                        + ":" + properties.chunkTokens() + ":" + properties.backgroundTokens()),
                hash(properties.qwenUrl() + ":" + properties.embeddingModel() + ":" + properties.dimensions() + ":" + properties.sourceTemplateVersion()),
                hash(properties.deepseekUrl() + ":" + properties.descriptionModel() + ":" + properties.descriptionTemplateVersion()),
                hash(properties.lexicalVersion()), hash(properties.qdrantUrl() + ":" + properties.dimensions() + ":bm25-idf-multilingual-v1"));
    }
    private String hash(String value) { return hashes.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private void enabled() { if (!properties.enabled()) throw new RetrievalException(DISABLED, "代码检索未启用"); }
    private RetrievalException safeFailure(RuntimeException exception) {
        return exception instanceof RetrievalException retrieval ? retrieval : new RetrievalException(SYNC_FAILED, "索引同步失败，请重新准备");
    }
    private record ScannedFile(String path, String version, String language, byte[] bytes) {}
}

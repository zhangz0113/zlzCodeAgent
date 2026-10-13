package com.zlzcode.codeagent.retrieval.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.tool.service.RunFileObservationService;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;

@Component
public final class FileIndexStore {
    private final ObjectMapper json;
    private final Path root;
    private final RunFileObservationService hashes;
    private final WorkspacePathGuard guard;
    public FileIndexStore(ObjectMapper json, RetrievalProperties properties,
                          RunFileObservationService hashes, WorkspacePathGuard guard) {
        this.json = json; this.root = Path.of(properties.storageDir()).toAbsolutePath().normalize();
        this.hashes = hashes; this.guard = guard;
    }
    public Path root() { return root; }
    public String identity(AuthorizedWorkspace workspace) {
        return hashes.sha256((workspace.id() + "\n" + guard.canonicalKey(workspace.root())).getBytes(StandardCharsets.UTF_8));
    }
    public String collection(AuthorizedWorkspace workspace) { return "code_retrieval_" + identity(workspace); }
    public Snapshot load(AuthorizedWorkspace workspace) {
        Path directory = root.resolve(identity(workspace));
        Path manifestPath = directory.resolve("manifest.json");
        if (!Files.exists(manifestPath)) return new Snapshot(null, new LinkedHashMap<>());
        try {
            Manifest manifest = json.readValue(manifestPath.toFile(), Manifest.class);
            if (manifest.formatVersion() != 1 || !workspace.id().equals(manifest.workspaceId())
                    || !guard.canonicalKey(workspace.root()).equals(manifest.rootKey())
                    || !collection(workspace).equals(manifest.collection())) {
                throw new RetrievalException(RetrievalException.Code.NEEDS_PREPARE, "索引归属不匹配，不能覆盖远端集合");
            }
            var files = new LinkedHashMap<String, FileRecord>();
            for (String path : manifest.files()) {
                FileRecord record = json.readValue(recordPath(directory, path).toFile(), FileRecord.class);
                if (!path.equals(record.path())) throw new IOException("record identity mismatch");
                files.put(path, record);
            }
            return new Snapshot(manifest, files);
        } catch (IOException exception) { throw storageFailure(); }
    }
    public void saveManifest(AuthorizedWorkspace workspace, Manifest manifest) {
        write(root.resolve(identity(workspace)).resolve("manifest.json"), manifest);
    }
    public void saveFile(AuthorizedWorkspace workspace, FileRecord record) {
        write(recordPath(root.resolve(identity(workspace)), record.path()), record);
    }
    public void removeFile(AuthorizedWorkspace workspace, String path) {
        try { Files.deleteIfExists(recordPath(root.resolve(identity(workspace)), path)); }
        catch (IOException exception) { throw storageFailure(); }
    }
    private Path recordPath(Path directory, String path) {
        return directory.resolve(hashes.sha256(path.getBytes(StandardCharsets.UTF_8)) + ".json");
    }
    /*
     * 背景：manifest 的待完成标记是远端覆盖重试依据，进程中断不能留下半份 JSON。
     * 设计意图：先落完整临时文件再替换记录，不逐字段修改正式文件。
     * 关键约束：远端写入前的标记必须先持久化；否则中断后旧记录会误报同步成功。
     */
    private void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            json.writeValue(temporary.toFile(), value);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) { throw storageFailure(); }
    }
    private RetrievalException storageFailure() {
        return new RetrievalException(RetrievalException.Code.SYNC_FAILED, "本地索引记录读写失败，请重新准备");
    }
}

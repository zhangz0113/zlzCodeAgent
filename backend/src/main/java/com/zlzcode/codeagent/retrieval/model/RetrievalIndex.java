package com.zlzcode.codeagent.retrieval.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RetrievalIndex {
    private RetrievalIndex() {}
    public enum State { UNPREPARED, READY, NEEDS_PREPARE, FAILED }
    public enum Operation { NONE, PREPARE, SEARCH }
    public enum Stage { IDLE, SCANNING, BUILDING, SYNCING, RETRIEVING, SELECTING, ASSEMBLING }
    public record Error(String code, String message) {}
    public record Status(boolean enabled, State state, Operation operation, Stage stage,
                         int processedFiles, Integer totalFiles, int indexedFiles, int skippedFiles,
                         int chunkCount, Instant lastSuccessAt, Error error) {}
    public record Range(int startByte, int endByte, int startLine, int endLine) {}
    public record Background(Range range, String source) {}
    public record Reference(String kind, String name, String receiverType, int argumentCount) {}
    public record Declaration(String kind, String name, String qualifiedName, String owner,
                              String chunkId, int parameterCount, List<String> parents, boolean exported) {}
    public record ModuleBinding(String module, String exportedName) {}
    public record Chunk(String id, String path, String fileVersion, String language, String kind,
                        String name, String owner, Range range, String source, boolean partialStructure,
                        List<Background> background, List<Reference> references, String description) {
        public Chunk described(String value) {
            return new Chunk(id, path, fileVersion, language, kind, name, owner, range, source,
                    partialStructure, background, references, value);
        }
    }
    public record FileRecord(String path, String version, String language, byte[] fullBytes,
                             String packageName, Map<String, String> imports,
                             Map<String, ModuleBinding> moduleBindings, Map<String, String> exports,
                             List<Declaration> declarations, List<Chunk> chunks) {
        public FileRecord withChunks(List<Chunk> replacements) {
            return new FileRecord(path, version, language, fullBytes, packageName, imports,
                    moduleBindings, exports, declarations, List.copyOf(replacements));
        }
    }
    public record Fingerprint(String structure, String embedding, String description,
                              String lexical, String collection) {}
    public record Manifest(int formatVersion, String workspaceId, String rootKey, String collection,
                           List<String> files, Fingerprint fingerprint, State state,
                           Instant lastSuccessAt, String pendingFile, Map<String, String> skipped) {}
    public record Snapshot(Manifest manifest, Map<String, FileRecord> files) {}

    public static String source(byte[] bytes, Range range) {
        return new String(bytes, range.startByte(), range.endByte() - range.startByte(), StandardCharsets.UTF_8);
    }
    public static Range range(byte[] bytes, int start, int end) {
        int first = 1;
        for (int i = 0; i < start; i++) if (bytes[i] == '\n') first++;
        int last = first;
        for (int i = start; i < Math.max(start, end - 1); i++) if (bytes[i] == '\n') last++;
        return new Range(start, end, first, last);
    }
}

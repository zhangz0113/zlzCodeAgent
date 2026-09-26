package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.agent.dto.RunResponse;
import com.zlzcode.codeagent.context.store.FileContextResultStore;
import com.zlzcode.codeagent.evaluation.model.EvaluationRun.Change;
import com.zlzcode.codeagent.memory.service.MemoryService;
import com.zlzcode.codeagent.session.model.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public final class EvaluationEvidenceService {
    private final ObjectMapper mapper;

    public EvaluationEvidenceService(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    /*
     * 背景：SSE 可能没有订阅者，压缩后的参数和结果也不包含完整原文。
     * 设计意图：从持久化 Session 和原文存储导出每步快照，而不是推测流中发生过什么。
     * 关键约束：缺失引用只能标注；不能拿摘要冒充原文，也不能把前一步记忆覆盖成最终状态。
     */
    public void captureStep(Path evidence, String stepId, RunResponse run, Session session,
                            FileContextResultStore payloads, MemoryService memories, List<String> missing) throws IOException {
        Path step = evidence.resolve("steps").resolve(stepId);
        write(step.resolve("run.json"), run);
        write(step.resolve("session.json"), session);
        write(step.resolve("memories.json"), memories.listApplicable(session.workspaceId()));
        if (run.finalAnswer() != null) Files.writeString(step.resolve("answer.md"), run.finalAnswer());
        List<Map<String, String>> references = new ArrayList<>();
        for (Session.Turn turn : session.turns()) {
            if (turn.assistant() == null) continue;
            for (Session.ToolRound round : turn.assistant().toolRounds()) {
                for (Session.ToolCall call : round.calls()) {
                    exportReference(evidence, session.sessionId(), call.argumentsRef(), "arguments", payloads, references, missing);
                    if (call.result() != null) {
                        exportReference(evidence, session.sessionId(), call.result().resultRef(), "result", payloads, references, missing);
                    }
                }
            }
        }
        write(step.resolve("references.json"), references);
    }

    private void exportReference(Path evidence, String sessionId, String reference, String role,
                                 FileContextResultStore payloads, List<Map<String, String>> references,
                                 List<String> missing) throws IOException {
        if (reference == null || reference.isBlank()) return;
        FileContextResultStore.StoredResult stored;
        try {
            stored = payloads.read(sessionId, reference);
        } catch (RuntimeException exception) {
            String absent = "payload:" + sessionId + "/" + reference;
            if (!missing.contains(absent)) missing.add(absent);
            return;
        }
        String relative = "payloads/" + sessionId + "/" + reference + ".json";
        write(evidence.resolve(relative), stored);
        references.add(Map.of("sessionId", sessionId, "reference", reference, "role", role, "path", relative));
    }

    /*
     * 背景：Agent 可以新增或移动未被 Git 跟踪的文件，验收构建又会生成额外产物。
     * 设计意图：在 worker 退出后固定完整文件集合，以独立拷贝判卷；diff 只辅助阅读。
     * 关键约束：调用者必须先确认进程退出，且不能在 artifact 内构建，否则证据会随执行改变。
     */
    public List<Change> captureArtifact(Path baseline, Path workspace, Path attempt) throws IOException, InterruptedException {
        Path artifact = attempt.resolve("artifact");
        copyTree(workspace, artifact);
        Map<String, String> before = treeHashes(baseline);
        Map<String, String> after = treeHashes(artifact);
        TreeSet<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        List<Change> changes = new ArrayList<>();
        for (String name : names) {
            String oldHash = before.get(name);
            String newHash = after.get(name);
            if (java.util.Objects.equals(oldHash, newHash)) continue;
            changes.add(new Change(name, oldHash == null ? "ADDED" : newHash == null ? "DELETED" : "MODIFIED", oldHash, newHash));
        }
        write(attempt.resolve("changes.json"), changes);
        Process diff = new ProcessBuilder("git", "diff", "--no-index", "--binary", "--no-ext-diff", "--no-textconv", "--",
                baseline.toAbsolutePath().toString(), artifact.toAbsolutePath().toString())
                .directory(attempt.toFile())
                .redirectOutput(attempt.resolve("changes.patch").toFile())
                .redirectError(attempt.resolve("changes-diff.log").toFile()).start();
        if (!diff.waitFor(30, TimeUnit.SECONDS)) {
            diff.destroyForcibly();
            diff.waitFor();
            throw new IOException("Artifact diff timed out");
        }
        if (diff.exitValue() > 1) throw new IOException("Artifact diff failed; inspect changes-diff.log");
        copyTree(artifact, attempt.resolve("verification-workspace"));
        return List.copyOf(changes);
    }

    public void copyTree(Path source, Path destination) throws IOException {
        Map<String, String> files = treeHashes(source);
        Files.createDirectory(destination);
        for (String name : files.keySet()) {
            Path target = destination.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(source.resolve(name), target);
        }
    }

    public Map<String, String> treeHashes(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var entries = Files.walk(root)) {
            for (Path path : entries.toList()) {
                if (Files.isSymbolicLink(path) || Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isOther()) throw new IOException("Linked or special evidence paths are unsupported: " + path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files.put(root.relativize(path).toString().replace('\\', '/'), sha256(path));
                }
            }
        }
        return files;
    }

    public String treeIdentity(Map<String, String> files) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(files).forEach((name, hash) -> canonical.append(name).append('\t').append(hash).append('\n'));
        return sha256(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256(Path path) throws IOException { return sha256(Files.readAllBytes(path)); }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation.ExperimentIdentity;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation.SnapshotFile;
import com.zlzcode.codeagent.evaluation.service.CaseCatalogService.LoadedCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class WorkspacePreparationService {
    private final ObjectMapper mapper;

    public WorkspacePreparationService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /*
     * 背景：同一道题必须从固定源码开始，验收程序与参考答案不能随案例包整体复制给 Agent。
     * 设计意图：核对清单后仅复制 workspace 下的源码，把任务、身份和快照清单放在其外侧。
     * 关键约束：不能覆盖旧输出或复制整个包；否则重复实验会继承修改，或把判卷答案泄露进授权工作区。
     */
    public PreparedEvaluation prepare(LoadedCase loaded, Path output, ExperimentIdentity identity) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Output requires a parent directory");
        Files.createDirectories(parent);
        target = parent.toRealPath().resolve(target.getFileName());
        if (target.startsWith(loaded.pack())) throw new IOException("Output must be outside the case package");
        if (Files.exists(target)) throw new IOException("Output already exists: " + target);
        List<SnapshotFile> files = loaded.manifest().files().stream()
                .sorted(Comparator.comparing(SnapshotFile::path)).toList();
        HashSet<String> names = new HashSet<>();
        StringBuilder manifestText = new StringBuilder();
        for (SnapshotFile file : files) {
            if (file.path() == null || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid snapshot file record");
            }
            if (!names.add(file.path())) throw new IOException("Duplicate snapshot file: " + file.path());
            Path source = CaseCatalogService.resolve(loaded.source(), file.path());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(Files.readAllBytes(source)).equals(file.sha256())) {
                throw new IOException("Snapshot content mismatch: " + file.path());
            }
            manifestText.append(file.path()).append('\t').append(file.sha256()).append('\n');
        }
        if (!loaded.manifest().snapshotId().equals("sha256:" + sha256(manifestText.toString().getBytes(StandardCharsets.UTF_8)))) {
            throw new IOException("Snapshot identity mismatch");
        }
        Files.createDirectory(target);
        Path workspace = Files.createDirectory(target.resolve("workspace"));
        for (SnapshotFile file : files) {
            Path source = CaseCatalogService.resolve(loaded.source(), file.path());
            Path destination = workspace.resolve(file.path()).normalize();
            if (!destination.startsWith(workspace)) throw new IOException("Invalid snapshot destination");
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
        }
        PreparedEvaluation prepared = new PreparedEvaluation(UUID.randomUUID().toString(), Instant.now().toString(),
                loaded.definition().id(), loaded.definition().version(), loaded.manifest().snapshotId(),
                workspace.toString(), identity);
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("prepared.json").toFile(), prepared);
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("snapshot.json").toFile(), loaded.manifest());
        Files.writeString(target.resolve("prompt.md"), loaded.prompt());
        return prepared;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}

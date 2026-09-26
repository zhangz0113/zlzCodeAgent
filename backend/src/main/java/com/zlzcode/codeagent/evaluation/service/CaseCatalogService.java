package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationCase;
import com.zlzcode.codeagent.evaluation.model.EvaluationSuite;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class CaseCatalogService {
    private final ObjectMapper mapper;

    public CaseCatalogService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LoadedCase loadCase(Path pack, String caseFile) throws IOException {
        Path root = pack.toRealPath();
        Path definitionPath = resolve(root, caseFile);
        EvaluationCase definition = mapper.readValue(definitionPath.toFile(), EvaluationCase.class);
        requireText(definition.id(), "case id");
        requireText(definition.category(), "case category");
        if (definition.version() < 1) throw new IOException("Case version must be positive");
        if (definition.verifier() == null || definition.verifier().command().isEmpty()
                || definition.verifier().timeoutSeconds() < 1) {
            throw new IOException("Case requires a verifier command and positive timeout");
        }
        for (String argument : definition.verifier().command()) requireText(argument, "verifier argument");
        Path workingDirectory = resolve(root, definition.verifier().workingDirectory());
        if (!Files.isDirectory(workingDirectory)) throw new IOException("Verifier working directory is not a directory");
        String prompt = Files.readString(resolve(root, definition.prompt()));
        requireText(prompt, "prompt");
        Path manifestPath = resolve(root, definition.snapshot());
        SnapshotManifest manifest = mapper.readValue(manifestPath.toFile(), SnapshotManifest.class);
        requireText(manifest.snapshotId(), "snapshot id");
        if (manifest.files().isEmpty()) throw new IOException("Snapshot contains no files");
        Path source = resolve(root, root.relativize(manifestPath.getParent().resolve("workspace")).toString());
        if (!Files.isDirectory(source)) throw new IOException("Snapshot workspace is not a directory");
        return new LoadedCase(root, definition, prompt, manifest, source);
    }

    public InspectedSuite inspect(Path pack, String suiteFile) throws IOException {
        Path root = pack.toRealPath();
        EvaluationSuite suite = mapper.readValue(resolve(root, suiteFile).toFile(), EvaluationSuite.class);
        requireText(suite.id(), "suite id");
        if (suite.version() < 1 || suite.cases().isEmpty()) throw new IOException("Suite requires a version and cases");
        List<EvaluationCase> cases = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (EvaluationSuite.CaseReference reference : suite.cases()) {
            EvaluationCase definition = loadCase(root, reference.path()).definition();
            if (!definition.id().equals(reference.id()) || definition.version() != reference.version()) {
                throw new IOException("Suite case identity does not match: " + reference.path());
            }
            if (!ids.add(definition.id())) throw new IOException("Duplicate suite case: " + definition.id());
            cases.add(definition);
        }
        return new InspectedSuite(suite, List.copyOf(cases));
    }

    /*
     * 背景：案例包同时保存源码和隐藏判卷材料，路径由可迁移的包描述提供。
     * 设计意图：统一解析真实路径而不是直接拼接，以便接受外部包同时约束其内部引用。
     * 关键约束：不能只检查字符串前缀或忽略符号链接；否则 ../ 或链接能把包外文件引入快照及命令材料。
     */
    public static Path resolve(Path root, String relative) throws IOException {
        requireText(relative, "relative path");
        Path name = Path.of(relative);
        if (name.isAbsolute()) throw new IOException("Expected a package-relative path");
        Path base = root.toRealPath();
        Path result = base.resolve(name).normalize().toRealPath();
        if (!result.startsWith(base)) throw new IOException("Path escapes package: " + relative);
        return result;
    }

    private static void requireText(String text, String label) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("Missing " + label);
    }

    public record LoadedCase(Path pack, EvaluationCase definition, String prompt,
                             SnapshotManifest manifest, Path source) {
    }

    public record InspectedSuite(EvaluationSuite suite, List<EvaluationCase> cases) {
    }
}

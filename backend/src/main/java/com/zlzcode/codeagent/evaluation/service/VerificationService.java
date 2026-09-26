package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.VerificationResult;
import com.zlzcode.codeagent.evaluation.model.VerificationResult.Status;
import com.zlzcode.codeagent.evaluation.service.CaseCatalogService.LoadedCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class VerificationService {
    private final ObjectMapper mapper;

    public VerificationService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /*
     * 背景：不同仓库使用不同构建与判卷工具，非零退出也可能只是环境不可用。
     * 设计意图：只执行固定案例声明的参数数组，以 JSON 合同读取业务结论，不在核心解析 Maven 等输出。
     * 关键约束：不能执行产物提供的命令或将退出码直接当成任务失败；否则 Agent 可改变判卷，环境故障也会污染成功率。
     */
    public VerificationResult verify(LoadedCase loaded, Path input, Path output, Map<String, String> environment)
            throws IOException, InterruptedException {
        return verify(loaded, input, output, environment, null);
    }

    public VerificationResult verify(LoadedCase loaded, Path input, Path output, Map<String, String> environment,
                                     Path evidence) throws IOException, InterruptedException {
        if (loaded.definition().verifier() == null) throw new IOException("This case requires manual review; use evaluation run");
        boolean requiresEvidence = loaded.definition().verifier().command().stream().anyMatch(value -> value.contains("${evidence}"));
        if (requiresEvidence && (evidence == null || !Files.isRegularFile(evidence))) {
            throw new IOException("Verifier requires an evidence manifest");
        }
        Path workspace = input.toRealPath();
        if (!Files.isDirectory(workspace)) throw new IOException("Workspace is not a directory");
        Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        target = target.getParent().toRealPath().resolve(target.getFileName());
        if (target.startsWith(workspace) || target.startsWith(loaded.pack())) {
            throw new IOException("Verification output must be outside the workspace and package");
        }
        Files.createDirectory(target);
        List<String> command = new ArrayList<>();
        for (String argument : loaded.definition().verifier().command()) {
            String expanded = argument.replace("${pack}", loaded.pack().toString())
                    .replace("${workspace}", workspace.toString()).replace("${output}", target.toString());
            if (evidence != null) expanded = expanded.replace("${evidence}", evidence.toRealPath().toString());
            command.add(expanded);
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(CaseCatalogService.resolve(loaded.pack(), loaded.definition().verifier().workingDirectory()).toFile())
                .redirectErrorStream(true).redirectOutput(target.resolve("process.log").toFile());
        builder.environment().putAll(environment);
        String startedAt = Instant.now().toString();
        long started = System.nanoTime();
        Integer exitCode = null;
        VerificationResult result;
        Process process = null;
        try {
            process = builder.start();
            if (!process.waitFor(loaded.definition().verifier().timeoutSeconds(), TimeUnit.SECONDS)) {
                stop(process);
                result = error("Verifier timed out");
            } else {
                exitCode = process.exitValue();
                Path resultFile = target.resolve("result.json");
                if (exitCode != 0) {
                    result = error("Verifier process exited with code " + exitCode);
                } else if (!Files.isRegularFile(resultFile)) {
                    result = error("Verifier did not produce result.json");
                } else {
                    result = mapper.readValue(resultFile.toFile(), VerificationResult.class);
                    validateResult(result, target);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            result = error("Verifier could not produce a valid result: " + exception.getMessage());
        } catch (InterruptedException exception) {
            if (process != null) stop(process);
            Thread.currentThread().interrupt();
            throw exception;
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("verification.json").toFile(), result);
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("execution.json").toFile(),
                new Execution(loaded.definition().id(), loaded.definition().version(), loaded.manifest().snapshotId(),
                        workspace.toString(), startedAt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), exitCode));
        return result;
    }

    private static void validateResult(VerificationResult result, Path output) throws IOException {
        if (result == null || result.status() == null) throw new IOException("Missing verification status");
        if (result.status() == Status.PASS && (result.checks().isEmpty()
                || result.checks().stream().anyMatch(check -> !check.passed()))) {
            throw new IOException("PASS requires successful checks");
        }
        for (VerificationResult.Check check : result.checks()) {
            if (check.id() == null || check.id().isBlank()) throw new IOException("Check requires an id");
        }
        for (String evidence : result.evidence()) CaseCatalogService.resolve(output, evidence);
    }

    private static VerificationResult error(String message) {
        return new VerificationResult(Status.ERROR, List.of(), List.of("process.log"), message);
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    public record Execution(String caseId, int caseVersion, String snapshotId, String workspace,
                            String startedAt, long durationMs, Integer exitCode) {
    }
}

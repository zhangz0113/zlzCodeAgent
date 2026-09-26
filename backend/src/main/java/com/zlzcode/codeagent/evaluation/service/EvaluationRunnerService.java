package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationRun.*;
import com.zlzcode.codeagent.evaluation.model.EvaluationRunConfig;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation.ExperimentIdentity;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class EvaluationRunnerService {
    private final ObjectMapper mapper;
    private final CaseCatalogService catalog;
    private final EvaluationEvidenceService files;
    private final EvaluationResultService results;

    public EvaluationRunnerService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.catalog = new CaseCatalogService(mapper);
        this.files = new EvaluationEvidenceService(mapper);
        this.results = new EvaluationResultService(mapper);
    }

    public Batch run(Path pack, String suiteFile, Path configFile, Path output, int repeat, Map<String, String> verifierEnvironment)
            throws IOException, InterruptedException {
        if (repeat < 1) throw new IOException("repeat must be positive");
        var inspected = catalog.inspect(pack, suiteFile);
        EvaluationRunConfig config = mapper.readValue(configFile.toFile(), EvaluationRunConfig.class).resolve(configFile);
        config.connection().normalizedApiKey();
        List<LoadedCase> cases = new ArrayList<>();
        for (var reference : inspected.suite().cases()) cases.add(catalog.loadCase(pack, reference.path()));
        Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        target = target.getParent().toRealPath().resolve(target.getFileName());
        if (target.startsWith(pack.toRealPath())) throw new IOException("Batch output must be outside the case package");
        Files.createDirectory(target);
        String batchId = "batch-" + UUID.randomUUID();
        String started = Instant.now().toString();
        String jarHash = EvaluationEvidenceService.sha256(Path.of(config.agentJar()));
        Map<String, String> packFiles = files.treeHashes(pack.toRealPath());
        String packHash = files.treeIdentity(packFiles);
        files.write(target.resolve("case-pack-files.json"), packFiles);
        List<BatchEntry> entries = new ArrayList<>();
        for (LoadedCase loaded : cases) {
            for (int index = 1; index <= repeat; index++) entries.add(new BatchEntry(loaded.definition().id(), loaded.definition().version(), index, null, "NOT_RUN", null));
        }
        files.write(target.resolve("batch.json"), new Batch(1, batchId, inspected.suite(), config, jarHash, packHash, started, null, List.copyOf(entries)));
        int cursor = 0;
        boolean stopped = false;
        for (LoadedCase loaded : cases) {
            for (int index = 1; index <= repeat; index++) {
                if (!stopped) {
                    String relative = "attempts/" + loaded.definition().id() + "/" + index;
                    Path attempt = target.resolve(relative);
                    Files.createDirectories(attempt);
                    BatchEntry entry;
                    try {
                        Result result = runAttempt(loaded, batchId, index, config, attempt, jarHash, packHash, verifierEnvironment);
                        entry = new BatchEntry(loaded.definition().id(), loaded.definition().version(), index, result.attemptId(), result.status().name(), relative + "/result.json");
                    } catch (IOException | IllegalArgumentException exception) {
                        files.write(attempt.resolve("preparation-error.json"), Map.of("status", "ERROR", "detail", String.valueOf(exception.getMessage())));
                        entry = new BatchEntry(loaded.definition().id(), loaded.definition().version(), index, null, "ERROR", relative + "/preparation-error.json");
                    }
                    entries.set(cursor, entry);
                    stopped = entry.status().equals("ERROR");
                    files.write(target.resolve("batch.json"), new Batch(1, batchId, inspected.suite(), config, jarHash, packHash, started, null, List.copyOf(entries)));
                }
                cursor++;
            }
        }
        Batch batch = new Batch(1, batchId, inspected.suite(), config, jarHash, packHash, started, Instant.now().toString(), List.copyOf(entries));
        files.write(target.resolve("batch.json"), batch);
        return batch;
    }

    private Result runAttempt(LoadedCase loaded, String batchId, int index, EvaluationRunConfig config, Path root,
                              String jarHash, String packHash, Map<String, String> environment) throws IOException, InterruptedException {
        long start = System.nanoTime();
        if (!jarHash.equals(EvaluationEvidenceService.sha256(Path.of(config.agentJar())))) throw new IOException("Agent JAR changed during the batch");
        var identity = new ExperimentIdentity(batchId, config.agentRevision(), config.model(), config.provider(), config.reasoningEffort(),
                config.runtimeProperties(), Map.of("runTimeoutSeconds", config.runTimeoutSeconds(), "modelTimeoutSeconds", config.modelTimeoutSeconds()));
        var prepared = new WorkspacePreparationService(mapper).prepare(loaded, root.resolve("prepared"), identity);
        Attempt attempt = new Attempt(batchId, index, prepared, loaded.definition(), config, jarHash, packHash);
        files.write(root.resolve("attempt.json"), attempt);
        WorkerRequest request = new WorkerRequest(1, prepared.attemptId(), root.toString(), prepared.workspace(), config, loaded.steps(), loaded.definition().approvalRules());
        files.write(root.resolve("worker-request.json"), request);
        Files.createDirectories(root.resolve("runtime"));
        Files.createDirectories(root.resolve("evidence"));
        long runStarted = System.nanoTime();
        WorkerResult worker = launch(request, root);
        long runMs = elapsed(runStarted);
        VerificationResult verification = null;
        long verificationMs = 0;
        try {
            files.captureArtifact(loaded.source(), Path.of(prepared.workspace()), root);
            files.write(root.resolve("evidence/manifest.json"), manifest(attempt, worker, runMs, 0, elapsed(start)));
            if (loaded.definition().verifier() != null) {
                long verificationStarted = System.nanoTime();
                verification = new VerificationService(mapper).verify(loaded, root.resolve("verification-workspace"),
                        root.resolve("verification"), environment, root.resolve("evidence/manifest.json"));
                verificationMs = elapsed(verificationStarted);
            }
        } catch (IOException exception) {
            worker = new WorkerResult(1, worker.attemptId(), worker.startedAt(), worker.completedAt(), worker.steps(),
                    worker.effectiveConfiguration(), worker.missingEvidence(), "Evidence or verification collection failed: " + exception.getMessage());
        }
        // 背景：父进程也可能在产物导出时失败。设计意图：另存汇总执行记录，保留子进程原始输出。
        // 关键约束：人工复核必须使用同一汇总输入，不能丢掉父进程错误后重新算成 PASS。
        files.write(root.resolve("execution.json"), worker);
        files.write(root.resolve("evidence/manifest.json"), manifest(attempt, worker, runMs, verificationMs, elapsed(start)));
        return results.saveInitial(root, attempt, worker, verification);
    }

    /*
     * 背景：异步 Agent 的文件写入不能与父进程判卷重叠，崩溃或超时也不能留下后台任务。
     * 设计意图：每次用一个 Java 进程，以明确退出作为固定产物的前提，兜底期限只记录执行环境错误。
     * 关键约束：停止后必须确认退出；若无法停止则抛错中止本批，不能继续拷贝可能变化的源码。
     */
    private WorkerResult launch(WorkerRequest request, Path root) throws IOException, InterruptedException {
        String started = Instant.now().toString();
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process;
        try {
            process = new ProcessBuilder(java, "-jar", request.config().agentJar(), "evaluation", "worker", "--request", root.resolve("worker-request.json").toString())
                    .directory(root.resolve("runtime").toFile()).redirectErrorStream(true)
                    .redirectOutput(root.resolve("runtime/process.log").toFile()).start();
        } catch (IOException exception) { return failedWorker(request, root, started, "Could not start worker: " + exception.getMessage()); }
        try {
            long limit = 90 + request.steps().size() * (request.config().runTimeoutSeconds() + 10);
            if (!process.waitFor(limit, TimeUnit.SECONDS)) {
                stop(process);
                return failedWorker(request, root, started, "Worker exceeded its process deadline");
            }
        } catch (InterruptedException exception) {
            stop(process);
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (process.exitValue() != 0) return failedWorker(request, root, started, "Worker process exited with code " + process.exitValue());
        try {
            WorkerResult result = mapper.readValue(root.resolve("worker-result.json").toFile(), WorkerResult.class);
            if (result == null || result.schemaVersion() != 1 || !request.attemptId().equals(result.attemptId()) || result.steps() == null) {
                return failedWorker(request, root, started, "Worker returned an invalid identity or schema");
            }
            return result;
        } catch (IOException exception) { return failedWorker(request, root, started, "Worker did not produce a valid result"); }
    }

    private WorkerResult failedWorker(WorkerRequest request, Path root, String started, String error) throws IOException {
        List<StepResult> steps = List.of();
        Path partial = root.resolve("evidence/steps.json");
        if (Files.isRegularFile(partial)) steps = mapper.readValue(partial.toFile(), new TypeReference<>() { });
        WorkerResult result = new WorkerResult(1, request.attemptId(), started, Instant.now().toString(), steps, Map.of(),
                List.of("Worker did not complete; existing runtime files are retained"), error);
        if (!Files.exists(root.resolve("worker-result.json"))) files.write(root.resolve("worker-result.json"), result);
        return result;
    }

    private static void stop(Process process) throws IOException, InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Worker could not be stopped; batch cannot continue");
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private Manifest manifest(Attempt attempt, WorkerResult worker, long runMs, long verificationMs, long totalMs) {
        return new Manifest(1, attempt, worker, worker.missingEvidence(),
                "Run metrics cover returned main-loop usage only; missing metrics are unknown; compaction and consolidation cost is not included",
                runMs, verificationMs, totalMs);
    }
}

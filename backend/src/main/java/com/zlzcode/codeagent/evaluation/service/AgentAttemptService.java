package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.CodeAgentApplication;
import com.zlzcode.codeagent.agent.dto.AgentRunRequest;
import com.zlzcode.codeagent.agent.dto.RunResponse;
import com.zlzcode.codeagent.agent.model.RunStatus;
import com.zlzcode.codeagent.agent.service.AgentRunService;
import com.zlzcode.codeagent.agent.service.ApprovalService;
import com.zlzcode.codeagent.agent.store.ApprovalStore;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.store.FileContextResultStore;
import com.zlzcode.codeagent.evaluation.model.EvaluationRun.*;
import com.zlzcode.codeagent.memory.config.MemoryProperties;
import com.zlzcode.codeagent.memory.service.MemoryService;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.session.service.SessionService;
import com.zlzcode.codeagent.tool.config.ToolProperties;
import com.zlzcode.codeagent.tool.registry.ToolRegistry;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentAttemptService {
    private final ObjectMapper mapper;

    public AgentAttemptService(ObjectMapper mapper) { this.mapper = mapper; }

    /*
     * 背景：应用服务会缓存授权、审批和记忆，评测不能借用日常状态，也不能靠清空文件复位。
     * 设计意图：每次 worker 建立无 Web 的真实上下文，以启动参数优先绑定全部状态路径。
     * 关键约束：不能用低优先级 defaultProperties 绑定路径；继承环境可能把写入重新指向日常目录。
     */
    public WorkerResult execute(WorkerRequest request) throws IOException {
        if (request.schemaVersion() != 1) throw new IOException("Unsupported worker request schema");
        Path attempt = Path.of(request.attemptDirectory()).toRealPath();
        Path workspacePath = Path.of(request.workspace()).toRealPath();
        if (!workspacePath.equals(attempt.resolve("prepared/workspace").toRealPath())) {
            throw new IOException("Worker may only register its prepared workspace");
        }
        var connection = request.config().connection();
        Path runtime = Files.createDirectories(attempt.resolve("runtime"));
        Path evidenceRoot = Files.createDirectories(attempt.resolve("evidence"));
        EvaluationEvidenceService evidence = new EvaluationEvidenceService(mapper);
        String started = Instant.now().toString();
        List<StepResult> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, Object> effective = new LinkedHashMap<>();
        String error = null;
        Map<String, String> properties = new LinkedHashMap<>(request.config().runtimeProperties());
        properties.put("spring.main.web-application-type", "none");
        properties.put("spring.main.banner-mode", "off");
        properties.put("codeagent.session.directory", runtime.resolve("sessions").toString());
        properties.put("codeagent.run.directory", runtime.resolve("runs").toString());
        properties.put("codeagent.approval.directory", runtime.resolve("approvals").toString());
        properties.put("codeagent.workspace.state-file", runtime.resolve("workspaces.json").toString());
        properties.put("codeagent.memory.state-file", runtime.resolve("memories.json").toString());
        properties.put("codeagent.context.result-directory", runtime.resolve("context-results").toString());
        properties.put("logging.file.name", runtime.resolve("application.log").toString());
        properties.put("codeagent.run.timeout", "PT" + request.config().runTimeoutSeconds() + "S");
        properties.put("codeagent.run.model-timeout", "PT" + request.config().modelTimeoutSeconds() + "S");
        properties.put("codeagent.run.max-concurrency", "1");
        String[] arguments = properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        SpringApplication application = new SpringApplication(CodeAgentApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        try (var context = application.run(arguments)) {
            WorkspaceRegistry registry = context.getBean(WorkspaceRegistry.class);
            AuthorizedWorkspace workspace = registry.register(workspacePath);
            SessionService sessions = context.getBean(SessionService.class);
            AgentRunService runs = context.getBean(AgentRunService.class);
            MemoryService memories = context.getBean(MemoryService.class);
            FileContextResultStore payloads = context.getBean(FileContextResultStore.class);
            EvaluationApprovalService approvals = new EvaluationApprovalService(context.getBean(ApprovalService.class),
                    context.getBean(ApprovalStore.class), evidence);
            effective.put("javaVersion", System.getProperty("java.version"));
            effective.put("context", context.getBean(ContextProperties.class));
            effective.put("memory", context.getBean(MemoryProperties.class));
            effective.put("tool", context.getBean(ToolProperties.class));
            effective.put("toolDeclarations", context.getBean(ToolRegistry.class).modelToolDeclarations());
            effective.put("runTimeoutSeconds", request.config().runTimeoutSeconds());
            effective.put("modelTimeoutSeconds", request.config().modelTimeoutSeconds());
            effective.put("model", request.config().model());
            effective.put("provider", request.config().provider());
            effective.put("reasoningEffort", request.config().reasoningEffort());
            evidence.write(evidenceRoot.resolve("effective-configuration.json"), effective);
            evidence.write(evidenceRoot.resolve("initial-state.json"), Map.of("workspaceId", workspace.id(),
                    "workspace", workspace.root().toString(), "memories", memories.listApplicable(workspace.id())));
            Map<String, String> sessionIds = new LinkedHashMap<>();
            boolean stopped = false;
            for (StepInput step : request.steps()) {
                if (stopped) {
                    results.add(new StepResult(step.id(), step.session(), null, "NOT_RUN", null, null));
                    continue;
                }
                String sessionId = sessionIds.computeIfAbsent(step.session(), ignored -> sessions.create(workspace.id()).sessionId());
                RunResponse run = runs.submit(new AgentRunRequest(sessionId, request.attemptId() + "-" + results.size(),
                        step.prompt(), request.config().model(), request.config().reasoningEffort(), connection));
                while (run.status() == RunStatus.RUNNING || run.status() == RunStatus.WAITING_APPROVAL) {
                    if (run.status() == RunStatus.WAITING_APPROVAL) approvals.decide(workspace.id(), run, request.approvalRules(), evidenceRoot);
                    Thread.sleep(200);
                    run = runs.read(sessionId, run.runId());
                }
                approvals.refresh(evidenceRoot);
                Session session = sessions.read(sessionId);
                evidence.captureStep(evidenceRoot, step.id(), run, session, payloads, memories, missing);
                results.add(new StepResult(step.id(), step.session(), sessionId, run.status().name(), run, "steps/" + step.id()));
                evidence.write(evidenceRoot.resolve("steps.json"), results);
                stopped = run.status() == RunStatus.FAILED;
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            error = "Worker failed: " + exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
            error = error.replace(connection.apiKey(), "<redacted>");
            missing.add("Worker did not complete normal evidence export; inspect runtime/process.log");
            for (StepInput step : request.steps()) {
                if (results.stream().noneMatch(result -> result.id().equals(step.id()))) {
                    results.add(new StepResult(step.id(), step.session(), null, "NOT_RUN", null, null));
                }
            }
        }
        WorkerResult result = new WorkerResult(1, request.attemptId(), started, Instant.now().toString(),
                List.copyOf(results), effective, List.copyOf(missing), error);
        evidence.write(attempt.resolve("worker-result.json"), result);
        return result;
    }
}

package com.zlzcode.codeagent.context.service;

import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.context.model.RunContextPlan;
import com.zlzcode.codeagent.context.model.RunContextPlan.ContextEntry;
import com.zlzcode.codeagent.context.model.RunContextPlan.MaterialKind;
import com.zlzcode.codeagent.context.model.RunContextPlan.MaterialScope;
import com.zlzcode.codeagent.memory.model.Memory;
import com.zlzcode.codeagent.memory.service.MemorySelectionService;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.session.model.SessionRunSnapshot;
import com.zlzcode.codeagent.validation.RequestContractException;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 准备一次 Run 的固定上下文材料和请求组装计划。
 */
@Component
public final class ModelRequestAssembler {

    private static final List<String> ROOT_INSTRUCTION_FILES = List.of("AGENTS.md", "agent.md");

    private final ConversationHistoryBuilder historyBuilder;
    private final MemorySelectionService memorySelectionService;

    public ModelRequestAssembler(
            ConversationHistoryBuilder historyBuilder,
            MemorySelectionService memorySelectionService) {
        this.historyBuilder = historyBuilder;
        this.memorySelectionService = memorySelectionService;
    }

    /*
     * 背景：Session 历史、派生检查点与项目规则来自不同生命周期，最终 ReAct 前缀仍需要固定顺序。
     * 设计意图：在工作区已授权后统一投影材料；压缩预检可重建候选计划，只有最终计划进入 ReActContext。
     * 关键约束：项目规则不能写入 Session Turn 或检查点，检查点必须位于当前规则之后、原文尾部之前；
     * 否则规则会跨 Run 重复累积，或让旧摘要获得覆盖当前项目约束的优先级。
     */
    public RunContextPlan prepare(
            AuthorizedWorkspace workspace,
            SessionRunSnapshot sessionRun,
            String systemPolicy) {
        Objects.requireNonNull(workspace, "Authorized workspace cannot be null");
        Objects.requireNonNull(sessionRun, "Session Run snapshot cannot be null");

        if (systemPolicy == null || systemPolicy.isBlank()
                || sessionRun.prompt() == null || sessionRun.prompt().isBlank()) {
            throw new RequestContractException("HISTORY_INVALID", "Agent 初始消息无效");
        }
        List<Session.Turn> visibleTurns = turnsAfterCheckpoint(sessionRun);
        List<LlmMessage> historyMessages = historyBuilder.projectTurns(
                visibleTurns, "history");
        List<ContextEntry> entries = new ArrayList<>(
                historyMessages.size() + ROOT_INSTRUCTION_FILES.size() + 3);

        entries.add(entry(
                new LlmMessage.TextMessage(LlmMessage.MessageRole.SYSTEM, systemPolicy),
                MaterialKind.SYSTEM_POLICY,
                "builtin:agent-policy",
                MaterialScope.APPLICATION));

        Path root = workspace.root().toAbsolutePath().normalize();
        for (String fileName : ROOT_INSTRUCTION_FILES) {
            loadProjectInstruction(root, fileName).ifPresent(entries::add);
        }
        entries.add(entry(
                environmentMessage(workspace, root),
                MaterialKind.WORKSPACE_ENVIRONMENT,
                "workspace:" + workspace.id() + "/environment",
                MaterialScope.WORKSPACE_ROOT));

        Memory.Selection memory = memorySelectionService.select(
                sessionRun.workspaceId(), sessionRun.prompt());
        if (!memory.empty()) {
            entries.add(entry(
                    new LlmMessage.TextMessage(
                            LlmMessage.MessageRole.USER, memory.promptContent()),
                    MaterialKind.LONG_TERM_MEMORY,
                    "memory:workspace:" + workspace.id() + "/selection",
                    MaterialScope.WORKSPACE_ROOT));
        }

        Session.HistoryCheckpoint checkpoint = sessionRun.historyCheckpoint();
        if (checkpoint != null) {
            String content = "Earlier completed conversation checkpoint. "
                    + "Newer raw turns and the current user prompt override conflicting details.\n\n"
                    + checkpoint.summaryContent();
            entries.add(entry(
                    new LlmMessage.TextMessage(LlmMessage.MessageRole.USER, content),
                    MaterialKind.SESSION_CHECKPOINT,
                    "session:" + sessionRun.sessionId() + "/checkpoint:"
                            + checkpoint.coveredThroughRunId(),
                    MaterialScope.SESSION));
        }

        for (int index = 0; index < historyMessages.size(); index++) {
            LlmMessage message = historyMessages.get(index);
            entries.add(new ContextEntry(
                    message,
                    MaterialKind.SESSION_HISTORY,
                    "session:" + sessionRun.sessionId() + "/history:" + index,
                    MaterialScope.SESSION,
                    relationIds(message)));
        }
        entries.add(entry(
                new LlmMessage.TextMessage(
                        LlmMessage.MessageRole.USER, sessionRun.prompt()),
                MaterialKind.CURRENT_USER,
                "run:" + sessionRun.runId() + "/user",
                MaterialScope.RUN));

        return new RunContextPlan(sessionRun.runId(), entries);
    }

    /*
     * 背景：检查点只替代它覆盖的连续 Completed Turn 前缀，原始 Session Turns 仍是权威记录。
     * 设计意图：由组装边界根据 coveredThroughRunId 选择较新的完整 Turn，而不是让摘要服务直接拼接协议消息。
     * 关键约束：覆盖边界之后的 Turn 必须全部按原顺序保留；若跳过或重排，检查点与原文之间会出现历史空洞。
     */
    private List<Session.Turn> turnsAfterCheckpoint(SessionRunSnapshot sessionRun) {
        Session.HistoryCheckpoint checkpoint = sessionRun.historyCheckpoint();
        if (checkpoint == null) {
            return sessionRun.completedHistory();
        }
        for (int index = 0; index < sessionRun.completedHistory().size(); index++) {
            Session.Turn turn = sessionRun.completedHistory().get(index);
            if (turn.runId().equals(checkpoint.coveredThroughRunId())) {
                return sessionRun.completedHistory().subList(
                        index + 1, sessionRun.completedHistory().size());
            }
        }
        throw new RequestContractException("HISTORY_INVALID", "历史检查点覆盖边界不存在");
    }

    private java.util.Optional<ContextEntry> loadProjectInstruction(Path root, String fileName) {
        Path source = root.resolve(fileName).normalize();
        if (Files.notExists(source)) {
            return java.util.Optional.empty();
        }
        try {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return java.util.Optional.empty();
            }
            String modelContent = "Project instructions from workspace root " + fileName
                    + ":\n\n" + content;
            return java.util.Optional.of(entry(
                    new LlmMessage.TextMessage(LlmMessage.MessageRole.SYSTEM, modelContent),
                    MaterialKind.PROJECT_INSTRUCTION,
                    "workspace:/" + fileName,
                    MaterialScope.WORKSPACE_ROOT));
        } catch (IOException exception) {
            throw new RequestContractException(
                    "CONTEXT_SOURCE_READ_FAILED",
                    "无法读取工作区项目指令: " + fileName);
        }
    }

    private LlmMessage environmentMessage(AuthorizedWorkspace workspace, Path root) {
        String content = "Workspace environment:\n"
                + "- name: " + workspace.name() + "\n"
                + "- root: " + root + "\n"
                + "- current working directory: " + root;
        return new LlmMessage.TextMessage(LlmMessage.MessageRole.SYSTEM, content);
    }

    private ContextEntry entry(
            LlmMessage message,
            MaterialKind kind,
            String source,
            MaterialScope scope) {
        return new ContextEntry(message, kind, source, scope, relationIds(message));
    }

    private List<String> relationIds(LlmMessage message) {
        if (message instanceof LlmMessage.AssistantToolCallsMessage assistant) {
            return assistant.toolCalls().stream().map(call -> call.id()).toList();
        }
        if (message instanceof LlmMessage.ToolResultMessage result) {
            return List.of(result.toolCallId());
        }
        return List.of();
    }
}

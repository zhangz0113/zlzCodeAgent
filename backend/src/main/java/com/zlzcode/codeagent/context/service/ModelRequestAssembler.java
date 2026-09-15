package com.zlzcode.codeagent.context.service;

import com.zlzcode.codeagent.agent.history.ConversationHistoryBuilder;
import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.context.model.RunContextPlan;
import com.zlzcode.codeagent.context.model.RunContextPlan.ContextEntry;
import com.zlzcode.codeagent.context.model.RunContextPlan.MaterialKind;
import com.zlzcode.codeagent.context.model.RunContextPlan.MaterialScope;
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

    public ModelRequestAssembler(ConversationHistoryBuilder historyBuilder) {
        this.historyBuilder = historyBuilder;
    }

    /*
     * 背景：Session 历史与项目规则来自不同生命周期，工具回合却必须反复使用同一份固定前缀。
     * 设计意图：在 Run 真正取得执行权且工作区已授权后读取一次根规则，并复用现有 History Builder
     * 建立初始消息，避免 Executor、ReActLoop 各自了解材料顺序。
     * 关键约束：该方法每个 Run 只能调用一次，且不能把项目规则写入普通 Session Turn；否则工具回合
     * 或后续 Run 会重复累积规则，提前引入尚未设计的刷新和去重语义。
     */
    public RunContextPlan prepare(
            AuthorizedWorkspace workspace,
            SessionRunSnapshot sessionRun,
            String systemPolicy) {
        Objects.requireNonNull(workspace, "Authorized workspace cannot be null");
        Objects.requireNonNull(sessionRun, "Session Run snapshot cannot be null");

        List<LlmMessage> historyMessages = historyBuilder.build(
                systemPolicy, sessionRun.completedHistory(), sessionRun.prompt());
        List<ContextEntry> entries = new ArrayList<>(
                historyMessages.size() + ROOT_INSTRUCTION_FILES.size() + 1);

        entries.add(entry(
                historyMessages.getFirst(),
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

        int currentPromptIndex = historyMessages.size() - 1;
        for (int index = 1; index < currentPromptIndex; index++) {
            LlmMessage message = historyMessages.get(index);
            entries.add(new ContextEntry(
                    message,
                    MaterialKind.SESSION_HISTORY,
                    "session:" + sessionRun.sessionId() + "/history:" + (index - 1),
                    MaterialScope.SESSION,
                    relationIds(message)));
        }
        entries.add(entry(
                historyMessages.get(currentPromptIndex),
                MaterialKind.CURRENT_USER,
                "run:" + sessionRun.runId() + "/user",
                MaterialScope.RUN));

        return new RunContextPlan(sessionRun.runId(), entries);
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

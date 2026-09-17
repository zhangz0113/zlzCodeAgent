package com.zlzcode.codeagent.session.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.session.exception.SessionException;
import com.zlzcode.codeagent.session.model.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public final class SessionStore {

    private static final int STATE_VERSION = 4;
    private static final int REFERENCE_STATE_VERSION = 3;
    private static final int STRUCTURED_STATE_VERSION = 2;
    private static final int LEGACY_STATE_VERSION = 1;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TURNS = 1_000;
    private static final int MAX_MESSAGE_CHARS = 100_000;
    private static final int MAX_TOOL_HISTORY = 15;
    private static final int MAX_TOOL_NAME_CHARS = 64;
    private static final int MAX_TOOL_VALUE_CHARS = 20_000;
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ObjectMapper objectMapper;
    private final Path directory;

    @Autowired
    public SessionStore(
            ObjectMapper objectMapper,
            @Value("${codeagent.session.directory:}") String configuredDirectory) {
        this(objectMapper, configuredDirectory == null || configuredDirectory.isBlank()
                ? defaultDirectory()
                : Path.of(configuredDirectory.trim()));
    }

    public SessionStore(ObjectMapper objectMapper, Path directory) {
        this.objectMapper = objectMapper;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Session read(String sessionId) {
        Path target = sessionFile(sessionId);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target)) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw SessionException.notFound();
                }
                throw SessionException.stateCorrupted();
            }
            long size = Files.size(target);
            if (size <= 0 || size > MAX_FILE_BYTES) {
                throw SessionException.stateCorrupted();
            }
            byte[] encoded = Files.readAllBytes(target);
            if (encoded.length == 0 || encoded.length > MAX_FILE_BYTES) {
                throw SessionException.stateCorrupted();
            }
            SessionFile file = objectMapper.readValue(encoded, SessionFile.class);
            return validateAndMap(file, sessionId);
        } catch (SessionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw SessionException.stateCorrupted();
        }
    }

    public void create(Session session) {
        write(session, false);
    }

    public void save(Session session) {
        write(session, true);
    }

    /*
     * 背景：Session 在模型调用前后都要落盘，进程中断不能破坏此前已经完成的会话历史。
     * 设计意图：把完整快照写入同目录临时文件并强制刷新，再用原子移动替换正式文件；不直接覆盖目标。
     * 关键约束：临时文件必须与目标同目录，且不允许在原子移动不受支持时降级，否则可能留下半个 JSON 文件。
     */
    private void write(Session session, boolean replaceExisting) {
        validateSession(session, SessionException::persistenceFailed);
        Path target = sessionFile(session.sessionId());
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(target)) {
                throw SessionException.stateCorrupted();
            }
            if (replaceExisting && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw SessionException.notFound();
            }
            if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw SessionException.persistenceFailed();
            }

            byte[] encoded = objectMapper.writeValueAsBytes(SessionFile.from(session));
            if (encoded.length == 0 || encoded.length > MAX_FILE_BYTES) {
                throw SessionException.persistenceFailed();
            }
            temporary = Files.createTempFile(directory, ".session-", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            if (replaceExisting) {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            temporary = null;
        } catch (SessionException exception) {
            throw exception;
        } catch (AtomicMoveNotSupportedException exception) {
            throw SessionException.persistenceFailed();
        } catch (IOException | RuntimeException exception) {
            throw SessionException.persistenceFailed();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Session validateAndMap(SessionFile file, String expectedSessionId) {
        /*
         * 背景：v1 只有扁平 toolHistory，v2 引入结构化工具 round，v3 增加 argumentsRef，v4 增加历史检查点。
         * 设计意图：新写入统一使用 v4，读取时在内存中把 v1-v3 投影为当前模型，不建设一次性迁移工具。
         * 关键约束：v1 的扁平顺序和 v2 的完整参数必须原样保留；不能把缺失 argumentsRef 当成损坏，
         * 也不能把旧版本缺失检查点当成损坏，否则升级后既有 Session 将全部无法继续读取。
         */
        if (file == null
                || (file.version() != STATE_VERSION
                && file.version() != REFERENCE_STATE_VERSION
                && file.version() != STRUCTURED_STATE_VERSION
                && file.version() != LEGACY_STATE_VERSION)
                || !expectedSessionId.equals(file.sessionId())) {
            throw SessionException.stateCorrupted();
        }
        Session session = new Session(
                file.sessionId(), file.workspaceId(), file.createdAt(), file.updatedAt(),
                file.version() == STATE_VERSION ? file.historyCheckpoint() : null,
                file.turns() == null ? List.of() : file.turns().stream()
                        .map(turn -> turn.toModel(file.version()))
                        .toList());
        validateSession(session, SessionException::stateCorrupted);
        return session;
    }

    private void validateSession(
            Session session,
            Supplier<SessionException> invalidState) {
        if (session == null
                || !validId(session.sessionId())
                || !validId(session.workspaceId())
                || session.createdAt() == null
                || session.updatedAt() == null
                || session.updatedAt().isBefore(session.createdAt())
                || session.turns().size() > MAX_TURNS) {
            throw invalidState.get();
        }
        Set<String> runIds = new HashSet<>();
        for (Session.Turn turn : session.turns()) {
            validateTurn(turn, runIds, invalidState);
        }
        validateHistoryCheckpoint(session, invalidState);
    }

    private void validateHistoryCheckpoint(
            Session session,
            Supplier<SessionException> invalidState) {
        Session.HistoryCheckpoint checkpoint = session.historyCheckpoint();
        if (checkpoint == null) {
            return;
        }
        if (invalidContent(checkpoint.summaryContent())
                || !validId(checkpoint.coveredThroughRunId())) {
            throw invalidState.get();
        }
        boolean coveredCompletedTurn = session.turns().stream()
                .anyMatch(turn -> turn.state() == Session.TurnState.COMPLETED
                        && turn.runId().equals(checkpoint.coveredThroughRunId()));
        if (!coveredCompletedTurn) {
            throw invalidState.get();
        }
    }

    private void validateTurn(
            Session.Turn turn,
            Set<String> runIds,
            Supplier<SessionException> invalidState) {
        if (turn == null
                || !validId(turn.runId())
                || !runIds.add(turn.runId())
                || turn.state() == null
                || turn.createdAt() == null
                || turn.user() == null
                || invalidContent(turn.user().content())) {
            throw invalidState.get();
        }
        if (turn.state() == Session.TurnState.INCOMPLETE) {
            if (turn.assistant() != null) {
                validateAssistant(turn.assistant(), false, invalidState);
            }
            return;
        }
        if (turn.assistant() == null || invalidContent(turn.assistant().content())) {
            throw invalidState.get();
        }
        validateAssistant(turn.assistant(), true, invalidState);
    }

    private void validateAssistant(
            Session.AssistantMessage assistant,
            boolean completed,
            Supplier<SessionException> invalidState) {
        if (assistant == null
                || (assistant.content() != null
                && assistant.content().length() > MAX_MESSAGE_CHARS)) {
            throw invalidState.get();
        }
        int toolCalls = 0;
        for (Session.ToolRound round : assistant.toolRounds()) {
            if (round == null
                    || round.calls().isEmpty()
                    || (round.assistantContent() != null
                    && round.assistantContent().length() > MAX_MESSAGE_CHARS)) {
                throw invalidState.get();
            }
            toolCalls += round.calls().size();
            if (toolCalls > MAX_TOOL_HISTORY) {
                throw invalidState.get();
            }
            for (Session.ToolCall call : round.calls()) {
                validateToolCall(call, completed, invalidState);
            }
        }
        if (assistant.content() == null && assistant.toolRounds().isEmpty()) {
            throw invalidState.get();
        }
    }

    private void validateToolCall(
            Session.ToolCall call,
            boolean completed,
            Supplier<SessionException> invalidState) {
        if (call == null
                || call.name() == null
                || call.name().isBlank()
                || call.name().length() > MAX_TOOL_NAME_CHARS
                || !TOOL_NAME_PATTERN.matcher(call.name()).matches()
                || call.arguments() == null
                || call.arguments().length() > MAX_TOOL_VALUE_CHARS
                || (call.argumentsRef() != null && call.argumentsRef().length() > 256)
                || (completed && call.result() == null)) {
            throw invalidState.get();
        }
        Session.ToolResult result = call.result();
        if (result == null) {
            return;
        }
        if (result.modelContent() == null
                || result.modelContent().length() > MAX_TOOL_VALUE_CHARS
                || (result.presentation() != null
                && result.presentation().length() > MAX_TOOL_VALUE_CHARS)
                || (result.resultRef() != null && result.resultRef().length() > 256)) {
            throw invalidState.get();
        }
    }

    private boolean invalidContent(String content) {
        return content == null || content.isBlank() || content.length() > MAX_MESSAGE_CHARS;
    }

    private boolean validId(String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    private Path sessionFile(String sessionId) {
        if (!validId(sessionId)) {
            throw SessionException.notFound();
        }
        return directory.resolve(sessionId + ".json");
    }

    private static Path defaultDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        if (workingDirectory.getFileName() != null
                && "backend".equalsIgnoreCase(workingDirectory.getFileName().toString())) {
            return workingDirectory.getParent().resolve("sessions");
        }
        return workingDirectory.resolve("sessions");
    }

    private record SessionFile(
            int version,
            String sessionId,
            String workspaceId,
            Instant createdAt,
            Instant updatedAt,
            Session.HistoryCheckpoint historyCheckpoint,
            List<TurnFile> turns) {

        private static SessionFile from(Session session) {
            return new SessionFile(
                    STATE_VERSION,
                    session.sessionId(),
                    session.workspaceId(),
                    session.createdAt(),
                    session.updatedAt(),
                    session.historyCheckpoint(),
                    session.turns().stream().map(TurnFile::from).toList());
        }
    }

    private record TurnFile(
            String runId,
            String state,
            Instant createdAt,
            Session.UserMessage user,
            AssistantFile assistant) {

        private static TurnFile from(Session.Turn turn) {
            return new TurnFile(
                    turn.runId(),
                    turn.state().name().toLowerCase(Locale.ROOT),
                    turn.createdAt(),
                    turn.user(),
                    turn.assistant() == null ? null : AssistantFile.from(turn.assistant()));
        }

        private Session.Turn toModel(int version) {
            Session.TurnState parsedState;
            try {
                parsedState = Session.TurnState.valueOf(state.toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw SessionException.stateCorrupted();
            }
            return new Session.Turn(
                    runId,
                    parsedState,
                    createdAt,
                    user,
                    assistant == null ? null : assistant.toModel(version));
        }
    }

    private record AssistantFile(
            String content,
            List<Session.ToolRound> toolRounds,
            List<Session.ToolHistory> toolHistory) {

        private static AssistantFile from(Session.AssistantMessage assistant) {
            return new AssistantFile(assistant.content(), assistant.toolRounds(), null);
        }

        private Session.AssistantMessage toModel(int version) {
            if (version >= STRUCTURED_STATE_VERSION) {
                return new Session.AssistantMessage(
                        content, toolRounds == null ? List.of() : toolRounds);
            }
            List<Session.ToolRound> legacyRounds = toolHistory == null
                    ? List.of()
                    : toolHistory.stream()
                    .map(tool -> new Session.ToolRound(
                            null,
                            List.of(new Session.ToolCall(
                                    tool.name(),
                                    tool.arguments(),
                                    null,
                                    new Session.ToolResult(
                                            true, tool.result(), null, null)))))
                    .toList();
            return new Session.AssistantMessage(content, legacyRounds);
        }
    }
}

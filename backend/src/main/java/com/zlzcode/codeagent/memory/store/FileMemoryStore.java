package com.zlzcode.codeagent.memory.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.memory.config.MemoryProperties;
import com.zlzcode.codeagent.memory.model.Memory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class FileMemoryStore {

    private static final int STATE_VERSION = 1;
    private static final int MAX_STATE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;
    private final Path stateFile;
    private final Clock clock;

    @Autowired
    public FileMemoryStore(ObjectMapper objectMapper, MemoryProperties properties) {
        this(objectMapper, properties, Path.of(properties.stateFile()), Clock.systemUTC());
    }

    FileMemoryStore(
            ObjectMapper objectMapper,
            MemoryProperties properties,
            Path stateFile,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public synchronized List<Memory.Entry> readAll() {
        return List.copyOf(readState().entries());
    }

    /*
     * 背景：长期记忆按逻辑键覆盖，模型一次归并可能同时新增、修正和删除多条记录。
     * 设计意图：在同一内存快照中应用整批操作，再一次性原子替换状态文件；不逐条覆盖正式 JSON。
     * 关键约束：逻辑键必须包含 scope、workspace、kind 和 subjectKey；若忽略其中任一维度，
     * 全局偏好与项目状态会互相覆盖，或中断时留下只应用了一半的归并结果。
     */
    public synchronized List<Memory.Change> apply(
            List<Memory.Operation> operations,
            String workspaceId,
            String sessionId,
            String runId) {
        Map<String, Memory.Entry> entries = new LinkedHashMap<>();
        for (Memory.Entry entry : readState().entries()) {
            entries.put(entry.logicalKey(), entry);
        }
        List<Memory.Change> changes = new ArrayList<>();
        Instant now = clock.instant();

        for (Memory.Operation operation : operations) {
            if (operation.type() == Memory.OperationType.NOOP) {
                continue;
            }
            String scopedWorkspace = operation.scope() == Memory.Scope.WORKSPACE
                    ? workspaceId : null;
            String logicalKey = logicalKey(
                    operation.scope(), scopedWorkspace, operation.kind(), operation.subjectKey());
            Memory.Entry existing = entries.get(logicalKey);
            if (operation.type() == Memory.OperationType.DELETE) {
                if (existing != null) {
                    entries.remove(logicalKey);
                    changes.add(new Memory.Change(Memory.ChangeType.DELETED, existing));
                }
                continue;
            }

            Memory.Entry updated = new Memory.Entry(
                    existing == null ? "memory-" + UUID.randomUUID() : existing.id(),
                    operation.scope(),
                    scopedWorkspace,
                    operation.kind(),
                    operation.subjectKey(),
                    operation.content(),
                    sessionId,
                    runId,
                    existing == null ? now : existing.createdAt(),
                    now);
            if (existing == null) {
                entries.put(logicalKey, updated);
                changes.add(new Memory.Change(Memory.ChangeType.ADDED, updated));
            } else if (!existing.content().equals(updated.content())) {
                entries.put(logicalKey, updated);
                changes.add(new Memory.Change(Memory.ChangeType.UPDATED, updated));
            }
        }

        if (!changes.isEmpty()) {
            if (entries.size() > properties.maxEntries()) {
                throw new IllegalStateException("Memory entry limit exceeded");
            }
            persist(new StateFile(STATE_VERSION, List.copyOf(entries.values())));
        }
        return List.copyOf(changes);
    }

    public synchronized boolean delete(String memoryId) {
        StateFile current = readState();
        List<Memory.Entry> retained = current.entries().stream()
                .filter(entry -> !entry.id().equals(memoryId))
                .toList();
        if (retained.size() == current.entries().size()) {
            return false;
        }
        persist(new StateFile(STATE_VERSION, retained));
        return true;
    }

    private StateFile readState() {
        try {
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                return new StateFile(STATE_VERSION, List.of());
            }
            if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(stateFile)) {
                throw new IllegalStateException("Memory state is unavailable");
            }
            byte[] encoded = Files.readAllBytes(stateFile);
            if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
                throw new IllegalStateException("Memory state is invalid");
            }
            StateFile state = objectMapper.readValue(encoded, StateFile.class);
            if (state == null || state.version() != STATE_VERSION
                    || state.entries() == null
                    || state.entries().size() > properties.maxEntries()) {
                throw new IllegalStateException("Memory state is invalid");
            }
            return new StateFile(STATE_VERSION, List.copyOf(state.entries()));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Memory state is invalid", exception);
        }
    }

    /*
     * 背景：记忆文件会跨 Run 长期复用，进程中断不能把此前有效的用户记忆截断成半个 JSON。
     * 设计意图：完整序列化到同目录临时文件后再替换正式文件；文件系统不支持原子移动时只做单次替换降级。
     * 关键约束：不得直接写正式文件，也不得把临时文件放到另一磁盘；否则崩溃或跨卷移动会破坏已有记忆。
     */
    private void persist(StateFile state) {
        Path temporary = null;
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(state);
            if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
                throw new IllegalStateException("Memory state is too large");
            }
            Path parent = stateFile.getParent();
            if (parent == null) {
                throw new IllegalStateException("Memory state file has no parent");
            }
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".memories-", ".tmp");
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist memory state", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String logicalKey(
            Memory.Scope scope,
            String workspaceId,
            Memory.Kind kind,
            String subjectKey) {
        return scope + "|" + (workspaceId == null ? "" : workspaceId)
                + "|" + kind + "|" + subjectKey;
    }

    private record StateFile(int version, List<Memory.Entry> entries) {
    }
}

package com.zlzcode.codeagent.memory.service;

import com.zlzcode.codeagent.memory.config.MemoryProperties;
import com.zlzcode.codeagent.memory.model.Memory;
import com.zlzcode.codeagent.memory.store.FileMemoryStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public final class MemoryService {

    private static final int MAX_SUBJECT_KEY_CHARS = 128;
    private static final int MAX_CONTENT_CHARS = 1000;
    private static final Pattern SUBJECT_KEY = Pattern.compile("^[a-z0-9][a-z0-9._:-]{0,127}$");
    private static final Pattern SENSITIVE_CONTENT = Pattern.compile(
            "(?i)(password|passwd|api[ _-]?key|access[ _-]?token|private[ _-]?key|secret|"
                    + "密码|口令|密钥|令牌|病史|诊断|疾病|用药|医疗记录|银行卡|信用卡|银行账号|"
                    + "收入|工资|贷款|财务状况|sk-[a-z0-9_-]{12,})");

    private final FileMemoryStore store;
    private final MemoryProperties properties;

    public MemoryService(FileMemoryStore store, MemoryProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public List<Memory.Entry> listApplicable(String workspaceId) {
        return store.readAll().stream()
                .filter(entry -> entry.scope() == Memory.Scope.GLOBAL
                        || Objects.equals(entry.workspaceId(), workspaceId))
                .sorted(Comparator.comparing(Memory.Entry::updatedAt).reversed())
                .toList();
    }

    public void delete(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return;
        }
        store.delete(memoryId);
    }

    public List<Memory.Change> apply(
            List<Memory.Operation> proposed,
            String workspaceId,
            String sessionId,
            String runId) {
        List<Memory.Operation> accepted = new ArrayList<>();
        for (Memory.Operation operation : proposed) {
            Memory.Operation normalized = normalize(operation);
            if (normalized != null) {
                accepted.add(normalized);
            }
            if (accepted.size() >= properties.consolidationMaxOperations()) {
                break;
            }
        }
        if (accepted.isEmpty()) {
            return List.of();
        }
        return store.apply(accepted, workspaceId, sessionId, runId);
    }

    private Memory.Operation normalize(Memory.Operation operation) {
        if (operation == null || operation.type() == null
                || operation.type() == Memory.OperationType.NOOP) {
            return null;
        }
        if (operation.scope() == null || operation.kind() == null
                || operation.subjectKey() == null) {
            return null;
        }
        String subjectKey = operation.subjectKey().trim().toLowerCase(Locale.ROOT);
        if (subjectKey.length() > MAX_SUBJECT_KEY_CHARS
                || !SUBJECT_KEY.matcher(subjectKey).matches()) {
            return null;
        }
        String content = operation.content() == null ? null : operation.content().trim();
        if (operation.type() == Memory.OperationType.UPSERT
                && (content == null || content.isBlank()
                || content.length() > MAX_CONTENT_CHARS
                || SENSITIVE_CONTENT.matcher(subjectKey + " " + content).find())) {
            return null;
        }
        return new Memory.Operation(
                operation.type(), operation.scope(), operation.kind(), subjectKey, content);
    }
}

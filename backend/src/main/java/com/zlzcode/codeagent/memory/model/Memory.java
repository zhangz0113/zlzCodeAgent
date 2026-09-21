package com.zlzcode.codeagent.memory.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class Memory {

    private Memory() {
    }

    public enum Scope {
        GLOBAL,
        WORKSPACE
    }

    public enum Kind {
        PREFERENCE,
        FACT,
        DECISION,
        STATUS
    }

    public enum OperationType {
        UPSERT,
        DELETE,
        NOOP
    }

    public enum ChangeType {
        ADDED,
        UPDATED,
        DELETED
    }

    public record Entry(
            String id,
            Scope scope,
            String workspaceId,
            Kind kind,
            String subjectKey,
            String content,
            String sourceSessionId,
            String sourceRunId,
            Instant createdAt,
            Instant updatedAt) {

        public Entry {
            requireText(id, "Memory ID");
            Objects.requireNonNull(scope, "Memory scope cannot be null");
            if (scope == Scope.WORKSPACE) {
                requireText(workspaceId, "Workspace memory workspace ID");
            } else {
                workspaceId = null;
            }
            Objects.requireNonNull(kind, "Memory kind cannot be null");
            requireText(subjectKey, "Memory subject key");
            requireText(content, "Memory content");
            requireText(sourceSessionId, "Memory source Session ID");
            requireText(sourceRunId, "Memory source Run ID");
            Objects.requireNonNull(createdAt, "Memory creation time cannot be null");
            Objects.requireNonNull(updatedAt, "Memory update time cannot be null");
        }

        public String logicalKey() {
            return scope + "|" + (workspaceId == null ? "" : workspaceId)
                    + "|" + kind + "|" + subjectKey;
        }
    }

    public record Operation(
            OperationType type,
            Scope scope,
            Kind kind,
            String subjectKey,
            String content) {

        public Operation {
            Objects.requireNonNull(type, "Memory operation type cannot be null");
        }
    }

    public record Change(ChangeType type, Entry entry) {

        public Change {
            Objects.requireNonNull(type, "Memory change type cannot be null");
            Objects.requireNonNull(entry, "Changed memory cannot be null");
        }
    }

    public record Selection(List<Entry> entries, String promptContent) {

        public Selection {
            entries = entries == null ? List.of() : List.copyOf(entries);
            promptContent = promptContent == null ? "" : promptContent;
        }

        public boolean empty() {
            return entries.isEmpty();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}

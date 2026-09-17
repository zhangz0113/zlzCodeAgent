package com.zlzcode.codeagent.context.model;

import com.zlzcode.codeagent.session.model.Session;

import java.util.List;
import java.util.Objects;

/**
 * 历史压缩过程中使用的不可变计划、候选和观察数据。
 */
public final class HistoryCompaction {

    private HistoryCompaction() {
    }

    public record Plan(
            Session.HistoryCheckpoint previousCheckpoint,
            List<Session.Turn> turnsToCompact,
            List<Session.Turn> retainedTail,
            int beforeTokens,
            int afterPhaseOneTokens,
            int targetTokens) {

        public Plan {
            turnsToCompact = List.copyOf(Objects.requireNonNull(
                    turnsToCompact, "Turns to compact cannot be null"));
            retainedTail = List.copyOf(Objects.requireNonNull(
                    retainedTail, "Retained tail cannot be null"));
            if (turnsToCompact.isEmpty()) {
                throw new IllegalArgumentException("Compaction requires at least one new Turn");
            }
        }
    }

    public record Candidate(
            Session.HistoryCheckpoint checkpoint,
            Integer inputTokens,
            Integer outputTokens) {

        public Candidate {
            Objects.requireNonNull(checkpoint, "History checkpoint cannot be null");
        }
    }

    public record Observation(
            int beforeTokens,
            int afterPhaseOneTokens,
            int compactedTurns,
            int retainedTurns,
            Integer summaryInputTokens,
            Integer summaryOutputTokens,
            int finalTokens) {
    }
}

package com.zlzcode.codeagent.memory.service;

import com.zlzcode.codeagent.memory.config.MemoryProperties;
import com.zlzcode.codeagent.memory.model.Memory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public final class MemorySelectionService {

    private static final Pattern LATIN_WORD = Pattern.compile("[a-z0-9._-]{2,}");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]");

    private final MemoryService memoryService;
    private final MemoryProperties properties;

    public MemorySelectionService(MemoryService memoryService, MemoryProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    public Memory.Selection select(String workspaceId, String prompt) {
        Set<String> promptTerms = terms(prompt);
        List<ScoredEntry> ranked = memoryService.listApplicable(workspaceId).stream()
                .map(entry -> new ScoredEntry(entry, relevance(entry, promptTerms)))
                .sorted(Comparator
                        .comparingInt((ScoredEntry value) -> globalPreference(value.entry())).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredEntry::relevance).reversed())
                        .thenComparingInt(value -> kindPriority(value.entry().kind()))
                        .thenComparing(value -> value.entry().updatedAt(), Comparator.reverseOrder()))
                .toList();

        List<Memory.Entry> selected = new ArrayList<>();
        int tokens = 0;
        for (ScoredEntry value : ranked) {
            if (selected.size() >= properties.maxSelectedEntries()) {
                break;
            }
            int entryTokens = estimateTokens(renderEntry(value.entry()));
            if (!selected.isEmpty()
                    && tokens + entryTokens > properties.selectionTokenBudget()) {
                continue;
            }
            selected.add(value.entry());
            tokens += entryTokens;
        }
        if (selected.isEmpty()) {
            return new Memory.Selection(List.of(), "");
        }

        StringBuilder content = new StringBuilder(
                "Long-term memory background. Use only when relevant. "
                        + "The current user request, project instructions, code and ADRs override conflicts.\n");
        for (Memory.Entry entry : selected) {
            content.append("- [")
                    .append(entry.scope()).append('/')
                    .append(entry.kind()).append('/')
                    .append(entry.subjectKey()).append("] ")
                    .append(entry.content()).append('\n');
        }
        return new Memory.Selection(selected, content.toString().trim());
    }

    private int globalPreference(Memory.Entry entry) {
        return entry.scope() == Memory.Scope.GLOBAL && entry.kind() == Memory.Kind.PREFERENCE
                ? 1 : 0;
    }

    private int relevance(Memory.Entry entry, Set<String> promptTerms) {
        Set<String> memoryTerms = terms(entry.subjectKey() + " " + entry.content());
        memoryTerms.retainAll(promptTerms);
        return memoryTerms.size();
    }

    private int kindPriority(Memory.Kind kind) {
        return switch (kind) {
            case DECISION -> 0;
            case STATUS -> 1;
            case FACT -> 2;
            case PREFERENCE -> 3;
        };
    }

    private Set<String> terms(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        Set<String> terms = new HashSet<>();
        Matcher words = LATIN_WORD.matcher(normalized);
        while (words.find()) {
            terms.add(words.group());
        }
        StringBuilder han = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            String character = normalized.substring(index, index + 1);
            if (CJK.matcher(character).matches()) {
                han.append(character);
            } else {
                addHanTerms(terms, han);
                han.setLength(0);
            }
        }
        addHanTerms(terms, han);
        return terms;
    }

    private void addHanTerms(Set<String> terms, StringBuilder text) {
        if (text.length() == 1) {
            terms.add(text.toString());
            return;
        }
        for (int index = 0; index + 1 < text.length(); index++) {
            terms.add(text.substring(index, index + 2));
        }
    }

    private String renderEntry(Memory.Entry entry) {
        return entry.scope() + " " + entry.kind() + " "
                + entry.subjectKey() + " " + entry.content();
    }

    private int estimateTokens(String content) {
        int tokens = 0;
        int nonCjkChars = 0;
        for (int index = 0; index < content.length(); index++) {
            String character = content.substring(index, index + 1);
            if (CJK.matcher(character).matches()) {
                tokens++;
            } else {
                nonCjkChars++;
            }
        }
        return tokens + Math.max(1, (nonCjkChars + 3) / 4);
    }

    private record ScoredEntry(Memory.Entry entry, int relevance) {
    }
}

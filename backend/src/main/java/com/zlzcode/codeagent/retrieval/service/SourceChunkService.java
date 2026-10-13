package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.context.service.ContextReductionModule;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.service.StructureParserService.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
public final class SourceChunkService {
    private final RetrievalProperties properties;
    private final StructureParserService parser;
    public SourceChunkService(RetrievalProperties properties, StructureParserService parser) {
        this.properties = properties; this.parser = parser;
    }
    public FileRecord build(String workspaceIdentity, String path, String version, String language, byte[] bytes) {
        Parsed parsed = Set.of("java", "javascript", "jsx", "typescript", "tsx").contains(language)
                ? parser.parse(bytes, language) : textParts(bytes, language);
        List<Chunk> chunks = new ArrayList<>();
        List<Declaration> declarations = new ArrayList<>();
        for (Part part : parsed.parts()) {
            if (part.end() <= part.start()) continue;
            List<Background> background = background(part, parsed.parts(), bytes);
            int start = part.start();
            String firstId = null;
            while (start < part.end()) {
                int end = splitEnd(bytes, start, part.end(), part.boundaries());
                String seed = workspaceIdentity + "\n" + path + "\n" + version + "\n" + part.kind() + "\n" + start + ":" + end;
                String id = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
                if (firstId == null) firstId = id;
                Range range = RetrievalIndex.range(bytes, start, end);
                chunks.add(new Chunk(id, path, version, language, part.kind(), part.name(), part.owner(), range,
                        RetrievalIndex.source(bytes, range), start != part.start() || end != part.end(),
                        background, part.references(), null));
                start = end;
            }
            if (!part.name().isEmpty()) {
                String qualified = (part.owner().isEmpty() ? "" : part.owner() + ".") + part.name();
                if (!parsed.packageName().isEmpty()) qualified = parsed.packageName() + "." + qualified;
                declarations.add(new Declaration(part.kind(), part.name(), qualified, part.owner(), firstId,
                        part.parameterCount(), part.parents(), part.exported() || parsed.exports().containsValue(part.name())));
            }
        }
        return new FileRecord(path, version, language, bytes, parsed.packageName(), parsed.imports(),
                parsed.bindings(), parsed.exports(), List.copyOf(declarations), List.copyOf(chunks));
    }
    public int splitEnd(byte[] bytes, int start, int end, List<Integer> boundaries) {
        String text = new String(bytes, start, end - start, StandardCharsets.UTF_8);
        if (fits(text)) return end;
        int max = start;
        for (int offset = start; offset < end;) {
            int next = offset + 1;
            while (next < end && (bytes[next] & 0xc0) == 0x80) next++;
            String slice = new String(bytes, start, next - start, StandardCharsets.UTF_8);
            if (!fits(slice)) break;
            max = next; offset = next;
        }
        if (max == start) throw new com.zlzcode.codeagent.retrieval.exception.RetrievalException(
                com.zlzcode.codeagent.retrieval.exception.RetrievalException.Code.BUDGET_EXCEEDED, "片段预算不足以容纳一个字符");
        int bound = start;
        for (int candidate : boundaries) if (candidate > start && candidate <= max) bound = Math.max(bound, candidate);
        if (bound > start) return bound;
        for (int i = max - 1; i >= start; i--) if (bytes[i] == '\n') return i + 1;
        return max;
    }
    private boolean fits(String text) {
        return text.codePointCount(0, text.length()) <= properties.chunkChars()
                && ContextReductionModule.estimateTextTokens(text) <= properties.chunkTokens();
    }
    private List<Background> background(Part part, List<Part> parts, byte[] bytes) {
        if (part.owner().isEmpty()) return List.of();
        List<Background> result = new ArrayList<>(); int used = 0;
        for (Part candidate : parts) {
            String fullName = (candidate.owner().isEmpty() ? "" : candidate.owner() + ".") + candidate.name();
            if (!(fullName.equals(part.owner()) && Set.of("type", "class", "interface").contains(candidate.kind())
                    || candidate.kind().equals("field") && candidate.owner().equals(part.owner())
                    && RetrievalIndex.source(bytes, RetrievalIndex.range(bytes, part.start(), part.end())).contains(candidate.name()))) continue;
            Range range = RetrievalIndex.range(bytes, candidate.start(), candidate.end());
            String source = RetrievalIndex.source(bytes, range);
            int tokens = ContextReductionModule.estimateTextTokens(source);
            if (used + tokens <= properties.backgroundTokens()) { result.add(new Background(range, source)); used += tokens; }
        }
        return List.copyOf(result);
    }
    private Parsed textParts(byte[] bytes, String language) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        String source = new String(bytes, StandardCharsets.UTF_8);
        int offset = 0;
        for (String line : source.split("(?<=\\n)", -1)) {
            boolean boundary = switch (language) {
                case "markdown" -> line.matches("(?s)^#{1,6}\\s+.*");
                case "yaml", "properties", "toml" -> line.matches("(?s)^(?:[\\w.-]+\\s*[:=]|\\[).*");
                case "json" -> line.matches("(?s)^ {0,2}\"[^\"]+\"\\s*:.*");
                case "xml" -> line.matches("(?s)^\\s{0,2}<[^/!?].*");
                default -> line.isBlank();
            };
            if (boundary && offset > 0) starts.add(offset);
            offset += line.getBytes(StandardCharsets.UTF_8).length;
        }
        starts.add(bytes.length);
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i + 1 < starts.size(); i++) {
            int start = starts.get(i), end = starts.get(i + 1);
            if (end <= start) continue;
            String text = new String(bytes, start, end - start, StandardCharsets.UTF_8);
            if (text.isBlank()) continue;
            String name = text.lines().findFirst().orElse("").strip();
            parts.add(new Part(start, end, "text", name, "", -1, List.of(), false, List.of(), List.of()));
        }
        return new Parsed("", Map.of(), Map.of(), Map.of(), parts);
    }
    public static String lexicalText(String raw) {
        String split = raw.replaceAll("([A-Z])([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
        return raw + "\n" + split;
    }
    public String documentInput(Chunk chunk) {
        return "Path: " + chunk.path() + "\nStructure: " + chunk.owner() + "." + chunk.name()
                + "\nSource:\n" + chunk.source() + "\nSame-file background:\n"
                + String.join("\n", chunk.background().stream().map(Background::source).toList());
    }
}

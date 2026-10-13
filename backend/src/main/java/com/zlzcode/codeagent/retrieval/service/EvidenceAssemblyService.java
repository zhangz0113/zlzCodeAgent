package com.zlzcode.codeagent.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.service.ContextReductionModule;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.model.RepositorySearch.*;
import com.zlzcode.codeagent.tool.service.RunFileObservationService;
import com.zlzcode.codeagent.workspace.model.AuthorizedWorkspace;
import com.zlzcode.codeagent.workspace.security.WorkspacePathGuard;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

@Service
public final class EvidenceAssemblyService {
    private final RetrievalProperties properties;
    private final ContextProperties context;
    private final WorkspacePathGuard guard;
    private final RunFileObservationService hashes;
    private final ObjectMapper json;
    public EvidenceAssemblyService(RetrievalProperties properties, ContextProperties context, WorkspacePathGuard guard,
                                   RunFileObservationService hashes, ObjectMapper json) {
        this.properties = properties; this.context = context; this.guard = guard; this.hashes = hashes; this.json = json;
    }
    public SearchDelivery assemble(AuthorizedWorkspace workspace, Scope scope, List<Candidate> selected,
                                   boolean candidateTruncated, WorkspaceIndexCoordinator.Lease lease) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (Candidate candidate : selected) {
            lease.check(); Chunk chunk = candidate.chunk();
            if (!scope.includes(chunk)) throw RetrievalException.invalid();
            if (!bytes.containsKey(chunk.path())) {
                try { bytes.put(chunk.path(), Files.readAllBytes(guard.resolveExisting(workspace.root(), chunk.path()))); }
                catch (java.io.IOException exception) { throw changed(); }
            }
            /*
             * 背景：编辑器可在索引同步后改动文件，旧行号不能用于当前新源码。
             * 设计意图：对最终选材文件重读完整字节并核对哈希，再提取范围，不直接交付 payload 缓存。
             * 关键约束：所有同文件片段必须匹配当前同一版本；跳过核对会混交源码并错误授予编辑资格。
             */
            if (!hashes.sha256(bytes.get(chunk.path())).equals(chunk.fileVersion())) throw changed();
        }
        List<Evidence> merged = merge(selected, bytes);
        List<Evidence> output = new ArrayList<>(); boolean truncated = false;
        for (Evidence evidence : merged) {
            for (Evidence piece : pieces(evidence, bytes.get(evidence.path()))) {
                List<Evidence> attempt = new ArrayList<>(output); attempt.add(piece);
                Result proposed = new Result(true, scope, attempt, new Limits(candidateTruncated, true));
                String encoded = encode(proposed);
                if (attempt.size() > properties.evidenceItems() || length(encoded) > Math.min(properties.evidenceChars(), context.spillThresholdChars())
                        || ContextReductionModule.estimateTextTokens(encoded) > properties.evidenceTokens()) {
                    truncated = true; continue;
                }
                output.add(piece);
            }
        }
        if (!selected.isEmpty() && output.isEmpty()) throw new RetrievalException(RetrievalException.Code.BUDGET_EXCEEDED, "证据预算无法容纳已选源码，请调整预算");
        Result result = new Result(true, scope, List.copyOf(output), new Limits(candidateTruncated, truncated));
        Map<String, ObservedSource> observations = new LinkedHashMap<>();
        output.forEach(e -> observations.putIfAbsent(e.path(), new ObservedSource(e.path(), bytes.get(e.path()))));
        return new SearchDelivery(result, List.copyOf(observations.values()));
    }
    private List<Evidence> merge(List<Candidate> candidates, Map<String, byte[]> bytes) {
        List<Evidence> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Chunk chunk = candidate.chunk(); Range range = chunk.range();
            Evidence next = evidence(chunk.id(), chunk.path(), chunk.fileVersion(), range.startByte(), range.endByte(),
                    bytes.get(chunk.path()), chunk.partialStructure(), candidate.roles(), candidate.relations());
            int earliest = -1;
            for (int i = 0; i < result.size(); i++) {
                Evidence prior = result.get(i);
                if (!prior.path().equals(next.path()) || !prior.fileVersion().equals(next.fileVersion())
                        || prior.endByte() <= next.startByte() || next.endByte() <= prior.startByte()) continue;
                var roles = new LinkedHashSet<>(prior.roles()); roles.addAll(next.roles());
                var relations = new LinkedHashSet<>(prior.relations()); relations.addAll(next.relations());
                Evidence combined = evidence(prior.id(), prior.path(), prior.fileVersion(), Math.min(prior.startByte(), next.startByte()),
                        Math.max(prior.endByte(), next.endByte()), bytes.get(prior.path()), prior.partialStructure() || next.partialStructure(),
                        List.copyOf(roles), List.copyOf(relations));
                if (length(encode(combined)) > itemBudget()) continue;
                next = combined;
                if (earliest < 0) earliest = i;
                result.remove(i--);
            }
            if (earliest < 0) result.add(next); else result.add(Math.min(earliest, result.size()), next);
        }
        return result;
    }
    private List<Evidence> pieces(Evidence original, byte[] bytes) {
        if (length(encode(original)) <= itemBudget()) return List.of(original);
        List<Evidence> result = new ArrayList<>();
        int start = original.startByte();
        while (start < original.endByte()) {
            int low = start, high = original.endByte(), best = start;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int end = mid;
                while (end > start && end < bytes.length && (bytes[end] & 0xc0) == 0x80) end--;
                Evidence part = evidence(original.id(), original.path(), original.fileVersion(), start, end, bytes, true, original.roles(), original.relations());
                if (length(encode(part)) <= itemBudget()) { best = Math.max(best, end); low = mid + 1; }
                else high = mid - 1;
            }
            if (best <= start) throw new RetrievalException(RetrievalException.Code.BUDGET_EXCEEDED, "单项证据预算不足，无法进行分页取回");
            for (int i = best - 1; i >= start; i--) if (bytes[i] == '\n') { best = i + 1; break; }
            result.add(evidence(original.id(), original.path(), original.fileVersion(), start, best, bytes, true, original.roles(), original.relations()));
            start = best;
        }
        return result;
    }
    private Evidence evidence(String id, String path, String version, int start, int end, byte[] bytes,
                              boolean partial, List<String> roles, List<Relation> relations) {
        Range range = RetrievalIndex.range(bytes, start, end);
        String identity = UUID.nameUUIDFromBytes((id + ":" + start + ":" + end).getBytes(StandardCharsets.UTF_8)).toString();
        return new Evidence(identity, path, version, start, end, range.startLine(), range.endLine(),
                RetrievalIndex.source(bytes, range), partial, roles, relations);
    }
    private int itemBudget() { return Math.min(properties.evidenceItemChars(), context.maxRetrievalChars() - 1200); }
    private int length(String value) { return value.codePointCount(0, value.length()); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot encode evidence"); }
    }
    private RetrievalException changed() { return new RetrievalException(RetrievalException.Code.SOURCE_CHANGED, "源码版本已变化，请重新检索"); }
}

package com.zlzcode.codeagent.retrieval.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import java.util.List;

public final class RepositorySearch {
    private RepositorySearch() {}
    public record Request(String query, String path, List<String> languages) {}
    public record Scope(String path, List<String> languages) {
        public boolean includes(Chunk chunk) {
            return (path.isEmpty() || chunk.path().equals(path) || chunk.path().startsWith(path + "/"))
                    && (languages.isEmpty() || languages.contains(chunk.language()));
        }
    }
    public record Relation(String kind, String path, String fileVersion, int startByte, int endByte, int startLine, int endLine) {
        public static Relation from(String kind, Chunk source) {
            return new Relation(kind, source.path(), source.fileVersion(), source.range().startByte(), source.range().endByte(),
                    source.range().startLine(), source.range().endLine());
        }
    }
    public record Candidate(Chunk chunk, List<String> roles, List<Relation> relations) {}
    public record Expansion(List<Candidate> primary, List<Candidate> candidates, boolean truncated) {}
    public record Evidence(String id, String path, String fileVersion, int startByte, int endByte,
                           int startLine, int endLine, String content, boolean partialStructure,
                           List<String> roles, List<Relation> relations) {}
    public record Limits(boolean candidateInputTruncated, boolean outputTruncated) {}
    public record Result(boolean ok, Scope scope, List<Evidence> evidence, Limits limits) {}
    public record ObservedSource(String path, @JsonIgnore byte[] fullBytes) {
        @Override public String toString() { return "ObservedSource[path=" + path + ", bytes=<internal>]"; }
    }
    public record SearchDelivery(Result result, @JsonIgnore List<ObservedSource> observedSources) {
        @Override public String toString() { return "SearchDelivery[evidenceCount=" + result.evidence().size() + "]"; }
    }
}

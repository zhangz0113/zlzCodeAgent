package com.zlzcode.codeagent.retrieval.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.service.SourceChunkService;
import com.zlzcode.codeagent.retrieval.service.WorkspaceIndexCoordinator.Lease;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.*;

@Component
public final class QdrantIndexClient {
    private final WebClient web;
    private final RetrievalProperties properties;
    public QdrantIndexClient(@Qualifier("retrievalQdrantWebClient") WebClient web, RetrievalProperties properties) {
        this.web = web; this.properties = properties;
    }
    public boolean exists(String collection, Lease lease) {
        JsonNode result = request(HttpMethod.GET, collection, "/exists", null, lease).path("result").path("exists");
        if (!result.isBoolean()) throw RetrievalException.invalid();
        return result.booleanValue();
    }
    public void create(String collection, Lease lease) {
        request(HttpMethod.PUT, collection, "", Map.of("vectors", Map.of(
                "source_dense", Map.of("size", properties.dimensions(), "distance", "Cosine"),
                "description_dense", Map.of("size", properties.dimensions(), "distance", "Cosine")),
                "sparse_vectors", Map.of("lexical_sparse", Map.of("modifier", "idf"))), lease);
        for (String field : List.of("path", "ancestor_dirs", "language"))
            request(HttpMethod.PUT, collection, "/index?wait=true", Map.of("field_name", field, "field_schema", "keyword"), lease);
    }
    public void deleteCollection(String collection, Lease lease) { request(HttpMethod.DELETE, collection, "", null, lease); }
    public void deleteFile(String collection, String path, Lease lease) {
        request(HttpMethod.POST, collection, "/points/delete?wait=true", Map.of("filter", Map.of("must",
                List.of(Map.of("key", "path", "match", Map.of("value", path))))), lease);
    }
    public void upsert(String collection, List<Map<String, Object>> points, Lease lease) {
        for (int i = 0; i < points.size(); i += 32)
            request(HttpMethod.PUT, collection, "/points?wait=true", Map.of("points", points.subList(i, Math.min(points.size(), i + 32))), lease);
    }
    public void updateRepresentations(String collection, List<Map<String, Object>> points, Lease lease) {
        for (Map<String, Object> point : points) {
            Map<?, ?> vectors = (Map<?, ?>) point.get("vector");
            if (!vectors.isEmpty()) request(HttpMethod.PUT, collection, "/points/vectors?wait=true",
                    Map.of("points", List.of(Map.of("id", point.get("id"), "vector", vectors))), lease);
            request(HttpMethod.POST, collection, "/points/payload?wait=true",
                    Map.of("points", List.of(point.get("id")), "payload", point.get("payload")), lease);
        }
    }
    public JsonNode retrieve(String collection, List<String> ids, boolean vectors, Lease lease) {
        JsonNode result = request(HttpMethod.POST, collection, "/points", Map.of("ids", ids,
                "with_payload", true, "with_vector", vectors), lease).path("result");
        if (!result.isArray()) throw RetrievalException.invalid();
        return result;
    }
    public static Map<String, Object> lexical(String raw) {
        return Map.of("text", SourceChunkService.lexicalText(raw), "model", "qdrant/bm25", "options",
                Map.of("tokenizer", "multilingual", "stemmer", Map.of("type", "none"), "stopwords", Map.of()));
    }
    public static Map<String, Object> payload(Chunk chunk) {
        var value = new LinkedHashMap<String, Object>();
        List<String> ancestors = new ArrayList<>(List.of(""));
        String path = chunk.path();
        for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) ancestors.add(path.substring(0, i));
        value.put("path", path); value.put("ancestor_dirs", ancestors); value.put("file_version", chunk.fileVersion());
        value.put("language", chunk.language()); value.put("kind", chunk.kind()); value.put("name", chunk.name());
        value.put("owner", chunk.owner()); value.put("startByte", chunk.range().startByte()); value.put("endByte", chunk.range().endByte());
        value.put("startLine", chunk.range().startLine()); value.put("endLine", chunk.range().endLine()); value.put("source", chunk.source());
        value.put("partialStructure", chunk.partialStructure()); value.put("background", chunk.background()); value.put("references", chunk.references());
        if (chunk.description() != null) value.put("description", chunk.description());
        return value;
    }
    public JsonNode request(HttpMethod method, String collection, String suffix, Object body, Lease lease) {
        var request = web.method(method).uri(properties.qdrantUrl().replaceAll("/+$", "") + "/collections/" + collection + suffix)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .headers(headers -> { if (!properties.qdrantApiKey().isBlank()) headers.set("api-key", properties.qdrantApiKey()); });
        try {
            JsonNode result = (body == null ? request : request.bodyValue(body)).exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(Mono.error(RetrievalException.dependency()));
                return response.bodyToMono(JsonNode.class);
            }).timeout(lease.remaining(properties.qdrantTimeout())).block();
            if (result == null || !result.has("result")) throw RetrievalException.invalid();
            return result;
        } catch (RetrievalException exception) { throw exception; }
        catch (RuntimeException exception) { lease.check(); throw RetrievalException.dependency(); }
    }
}

package com.zlzcode.codeagent.retrieval.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.zlzcode.codeagent.openai.client.OpenAiTransportClient;
import com.zlzcode.codeagent.openai.dto.OpenAiConnectionInput;
import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.service.WorkspaceIndexCoordinator.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class EmbeddingClient {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingClient.class);
    private final OpenAiTransportClient transport;
    private final RetrievalProperties properties;
    public EmbeddingClient(OpenAiTransportClient transport, RetrievalProperties properties) {
        this.transport = transport; this.properties = properties;
    }
    public List<List<Double>> documents(List<String> texts, Lease lease) {
        List<List<Double>> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += properties.embeddingBatchSize())
            result.addAll(embed(texts.subList(i, Math.min(texts.size(), i + properties.embeddingBatchSize())), false, lease));
        return result;
    }
    public List<Double> query(String question, Lease lease) { return embed(List.of(question), true, lease).getFirst(); }
    private List<List<Double>> embed(List<String> texts, boolean query, Lease lease) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("dimension", properties.dimensions()); parameters.put("output_type", "dense");
        parameters.put("text_type", query ? "query" : "document");
        if (query) parameters.put("instruct", properties.queryInstruction());
        long started = System.nanoTime();
        JsonNode response;
        try {
            response = transport.postJson(new OpenAiConnectionInput(properties.qwenUrl(), properties.qwenApiKey()),
                    "/api/v1/services/embeddings/text-embedding/text-embedding",
                    Map.of("model", properties.embeddingModel(), "input", Map.of("texts", texts), "parameters", parameters),
                    lease.remaining(properties.modelTimeout()), status -> RetrievalException.dependency()).block();
        } catch (RuntimeException exception) { lease.check(); throw RetrievalException.dependency(); }
        if (response == null) throw RetrievalException.invalid();
        JsonNode embeddings = response.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != texts.size()) throw RetrievalException.invalid();
        var result = new ArrayList<List<Double>>(Collections.nCopies(texts.size(), null));
        /*
         * 背景：千问原生批量响应使用 index，返回顺序不承诺等同输入顺序。
         * 设计意图：逐项校验并按 index 关联，不将缺失序号默认为零。
         * 关键约束：数量、唯一序号、维度和有限值必须同时成立；否则向量会绑定错误源码。
         */
        for (JsonNode item : embeddings) {
            JsonNode index = item.path("index"), vector = item.path("embedding");
            if (!index.isInt() || index.intValue() < 0 || index.intValue() >= texts.size()
                    || result.get(index.intValue()) != null || !vector.isArray()
                    || vector.size() != properties.dimensions()) throw RetrievalException.invalid();
            List<Double> values = new ArrayList<>();
            for (JsonNode value : vector) {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw RetrievalException.invalid();
                values.add(value.doubleValue());
            }
            result.set(index.intValue(), List.copyOf(values));
        }
        LOG.info("retrieval embedding role={} inputs={} elapsedMs={} tokens={}", query ? "query" : "document",
                texts.size(), (System.nanoTime() - started) / 1_000_000, response.path("usage").path("total_tokens").asLong());
        return List.copyOf(result);
    }
}

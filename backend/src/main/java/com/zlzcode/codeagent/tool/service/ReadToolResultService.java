package com.zlzcode.codeagent.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.exception.ContextReductionException;
import com.zlzcode.codeagent.context.model.ContextReduction.ContentRange;
import com.zlzcode.codeagent.context.model.ContextReduction.ResultReference;
import com.zlzcode.codeagent.context.model.ContextReduction.RetrievedContent;
import com.zlzcode.codeagent.context.service.ContextReductionModule;
import com.zlzcode.codeagent.tool.config.ToolProperties;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.ToolCompleted;
import com.zlzcode.codeagent.tool.model.ToolExecutionContext;
import com.zlzcode.codeagent.tool.model.ToolExecutionResult;
import com.zlzcode.codeagent.tool.model.ToolOutcome;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.TimeoutException;

@Service
public final class ReadToolResultService implements ToolHandler {

    private final ObjectMapper objectMapper;
    private final ContextReductionModule contextReduction;
    private final ToolProperties toolProperties;
    private final int defaultLimit;

    public ReadToolResultService(
            ObjectMapper objectMapper,
            ContextReductionModule contextReduction,
            ToolProperties toolProperties,
            ContextProperties contextProperties) {
        this.objectMapper = objectMapper;
        this.contextReduction = contextReduction;
        this.toolProperties = toolProperties;
        this.defaultLimit = Math.min(4000, contextProperties.maxRetrievalChars());
    }

    /*
     * 背景：结果引用指向工具当时交付的快照，普通 read 读取当前工作区文件，二者不能
     * 互相替代；本地文件读取仍属于阻塞 I/O。
     * 设计意图：Handler 只作为 Tool seam 的 Adapter，把已校验参数交给统一 Module，
     * 并在 boundedElastic 上返回有界片段。
     * 关键约束：引用授权必须使用当前 Session，且该工具结果不能再次进入 Spill；否则
     * 会泄露其他会话内容或形成取回结果反复外置的循环。
     */
    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.<ToolExecutionResult>fromCallable(() -> new ToolCompleted(read(context, arguments)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(toolProperties.executionTimeout())
                .onErrorResume(ContextReductionException.class, exception -> Mono.just(
                        new ToolCompleted(ToolOutcome.failure(
                                objectMapper,
                                exception.code(),
                                exception.safeMessage(),
                                "The stored tool result could not be retrieved."))))
                .onErrorResume(TimeoutException.class, exception -> Mono.just(
                        new ToolCompleted(ToolOutcome.failure(
                                objectMapper,
                                "TOOL_TIMEOUT",
                                "读取工具结果超时",
                                "The stored tool result could not be read before the tool timeout."))));
    }

    private ToolOutcome read(ToolExecutionContext context, String arguments) {
        ReadArguments parsed = parse(arguments);
        RetrievedContent result = contextReduction.retrieveToolResult(
                context.sessionId(),
                new ResultReference(parsed.resultRef()),
                new ContentRange(parsed.offset(), parsed.limit()));
        return new ToolOutcome(
                true,
                encode(new ReadResult(
                        true,
                        result.resultRef(),
                        result.offset(),
                        result.nextOffset(),
                        result.totalChars(),
                        result.complete(),
                        result.content())),
                "已读取工具结果字符 " + result.offset() + "-" + result.nextOffset()
                        + (result.complete() ? "；已到结尾" : ""));
    }

    private ReadArguments parse(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            int offset = node.has("offset") ? node.get("offset").intValue() : 0;
            int limit = node.has("limit") ? node.get("limit").intValue() : defaultLimit;
            return new ReadArguments(node.get("result_ref").asText(), offset, limit);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Validated read_tool_result arguments could not be decoded", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码 read_tool_result 结果", exception);
        }
    }

    private record ReadArguments(String resultRef, int offset, int limit) {
    }

    private record ReadResult(
            boolean ok,
            String resultRef,
            int offset,
            int nextOffset,
            int totalChars,
            boolean complete,
            String content) {
    }
}

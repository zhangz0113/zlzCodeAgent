package com.zlzcode.codeagent.retrieval.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RepositorySearch.Request;
import com.zlzcode.codeagent.retrieval.service.RepositorySearchService;
import com.zlzcode.codeagent.tool.handler.ToolHandler;
import com.zlzcode.codeagent.tool.model.*;
import com.zlzcode.codeagent.tool.service.RunFileObservationService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class RepositorySearchToolHandler implements ToolHandler {
    private final ObjectMapper json;
    private final RepositorySearchService search;
    private final RunFileObservationService observations;
    public RepositorySearchToolHandler(ObjectMapper json, RepositorySearchService search, RunFileObservationService observations) {
        this.json = json; this.search = search; this.observations = observations;
    }
    @Override public Mono<ToolExecutionResult> execute(ToolExecutionContext context, String arguments) {
        return Mono.defer(() -> {
            final Request request;
            try { request = json.readValue(arguments, Request.class); }
            catch (java.io.IOException exception) { return Mono.just(failure("TOOL_ARGUMENTS_INVALID", "检索参数无效")); }
            return search.search(context.workspace(), request).<ToolExecutionResult>map(delivery -> {
                final String encoded;
                try { encoded = json.writeValueAsString(delivery.result()); }
                catch (java.io.IOException exception) { throw new IllegalStateException("无法编码仓库证据"); }
                /*
                 * 背景：索引扫描和模型查看不授予当前 Run 编辑资格，只有实际交付源码构成读取事实。
                 * 设计意图：先成功编码结果，再登记最终文件完整字节，不强迫 Agent 为资格重复 read。
                 * 关键约束：不得登记候选/扩展但未交付的文件，也不能在编码失败前登记；否则审批可基于未读内容修改。
                 */
                delivery.observedSources().forEach(source -> observations.record(context, source.path(), source.fullBytes()));
                return new ToolCompleted(new ToolOutcome(true, encoded, "已交付 " + delivery.result().evidence().size() + " 项仓库源码依据"));
            });
        }).onErrorResume(RetrievalException.class, exception -> Mono.just(failure(exception.code(), exception.getMessage())))
                .onErrorResume(IllegalArgumentException.class, exception -> Mono.just(failure("WORKSPACE_PATH_INVALID", "检索路径或范围无效")))
                .onErrorResume(java.io.UncheckedIOException.class, exception -> {
                    String code = exception.getCause() instanceof java.nio.file.NoSuchFileException ? "PATH_NOT_FOUND"
                            : exception.getCause() instanceof java.nio.file.FileSystemException ? "PATH_NOT_SUPPORTED" : "FILE_ACCESS_FAILED";
                    return Mono.just(failure(code, "检索路径不存在或不可访问"));
                });
    }
    private ToolCompleted failure(String code, String message) { return new ToolCompleted(ToolOutcome.failure(json, code, message, message)); }
}

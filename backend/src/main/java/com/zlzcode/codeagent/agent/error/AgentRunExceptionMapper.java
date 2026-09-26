package com.zlzcode.codeagent.agent.error;

import com.zlzcode.codeagent.agent.dto.AgentEvent;
import com.zlzcode.codeagent.agent.exception.ApprovalException;
import com.zlzcode.codeagent.agent.exception.RunException;
import com.zlzcode.codeagent.context.exception.ContextReductionException;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.exception.SessionException;
import com.zlzcode.codeagent.workspace.exception.WorkspaceRegistryException;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public final class AgentRunExceptionMapper {

    /*
     * 背景：SSE 已开始输出后，普通 HTTP 异常处理器无法再改变响应，只能发送 Agent 错误事件。
     * 设计意图：集中定义运行边界允许公开的异常映射，避免 Agent 服务重复判断异常类型。
     * 关键约束：未知异常必须原样传播；将程序缺陷伪装成 INTERNAL_ERROR 会阻断日志、监控和测试发现问题。
     */
    public Mono<AgentEvent.Error> mapException(Throwable exception) {
        return mapKnownException(exception).map(Mono::just).orElseGet(() -> Mono.error(exception));
    }

    /*
     * 背景：运行失败回调可能位于 Reactor 非阻塞线程，而异常分类本身没有异步操作。
     * 设计意图：同步提供已有分类，保留响应式入口的未知异常传播语义，避免调用方阻塞取值。
     * 关键约束：此处不能加入 I/O 或 block；否则错误映射会再次抛错，丢失原始错误码及失败记录。
     */
    public Optional<AgentEvent.Error> mapKnownException(Throwable exception) {
        if (exception instanceof RunException runException && RunException.TIMEOUT_CODE.equals(runException.code())) {
            return Optional.of(new AgentEvent.Error(runException.getMessage(), runException.code(), false));
        }
        if (exception instanceof OpenAiIntegrationException openAiException) {
            return Optional.of(new AgentEvent.Error(
                    openAiException.safeMessage(),
                    openAiException.code(),
                    openAiException.retryable()));
        }
        if (exception instanceof WorkspaceRegistryException workspaceException) {
            return Optional.of(new AgentEvent.Error(
                    workspaceException.getMessage(),
                    workspaceException.code(),
                    workspaceException.retryable()));
        }
        if (exception instanceof SessionException sessionException) {
            return Optional.of(new AgentEvent.Error(
                    sessionException.getMessage(),
                    sessionException.code(),
                    sessionException.retryable()));
        }
        if (exception instanceof ApprovalException approvalException) {
            return Optional.of(new AgentEvent.Error(
                    approvalException.getMessage(),
                    approvalException.code(),
                    approvalException.retryable()));
        }
        if (exception instanceof ContextReductionException contextException) {
            return Optional.of(new AgentEvent.Error(
                    contextException.safeMessage(),
                    contextException.code(),
                    contextException.retryable()));
        }
        return Optional.empty();
    }
}

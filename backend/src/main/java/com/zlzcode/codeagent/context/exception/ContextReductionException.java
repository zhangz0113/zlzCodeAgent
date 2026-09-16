package com.zlzcode.codeagent.context.exception;

public final class ContextReductionException extends RuntimeException {

    private final String code;
    private final String safeMessage;
    private final boolean retryable;

    private ContextReductionException(
            String code,
            String safeMessage,
            boolean retryable,
            Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.safeMessage = safeMessage;
        this.retryable = retryable;
    }

    public static ContextReductionException capacityExceeded() {
        return new ContextReductionException(
                "CONTEXT_CAPACITY_EXCEEDED",
                "模型请求在允许的内容缩减后仍超过容量限制",
                false,
                null);
    }

    public static ContextReductionException storeFailed(Throwable cause) {
        return new ContextReductionException(
                "CONTEXT_RESULT_STORE_FAILED",
                "无法保存可取回的工具结果",
                true,
                cause);
    }

    public static ContextReductionException resultNotFound() {
        return new ContextReductionException(
                "CONTEXT_RESULT_NOT_FOUND",
                "工具结果引用不可用",
                false,
                null);
    }

    public static ContextReductionException rangeInvalid() {
        return new ContextReductionException(
                "CONTEXT_RESULT_RANGE_INVALID",
                "工具结果读取范围无效",
                false,
                null);
    }

    public static ContextReductionException readFailed(Throwable cause) {
        return new ContextReductionException(
                "CONTEXT_RESULT_READ_FAILED",
                "无法读取已保存的工具结果",
                true,
                cause);
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}

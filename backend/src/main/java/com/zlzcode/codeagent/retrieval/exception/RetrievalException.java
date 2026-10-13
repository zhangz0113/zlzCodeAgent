package com.zlzcode.codeagent.retrieval.exception;

public final class RetrievalException extends RuntimeException {
    public enum Code {
        DISABLED, NOT_READY, BUSY, NEEDS_PREPARE, SCAN_FAILED, SYNC_FAILED,
        DEPENDENCY_FAILED, RESPONSE_INVALID, SOURCE_CHANGED, BUDGET_EXCEEDED, TIMEOUT
    }
    private final Code reason;
    public RetrievalException(Code reason, String message) {
        super(message);
        this.reason = reason;
    }
    public Code reason() { return reason; }
    public String code() { return "RETRIEVAL_" + reason.name(); }
    public boolean retryable() { return reason != Code.DISABLED && reason != Code.RESPONSE_INVALID; }
    public static RetrievalException dependency() {
        return new RetrievalException(Code.DEPENDENCY_FAILED, "检索依赖请求失败，请检查服务配置后重试");
    }
    public static RetrievalException invalid() {
        return new RetrievalException(Code.RESPONSE_INVALID, "检索依赖返回了不符合协议的结果");
    }
}

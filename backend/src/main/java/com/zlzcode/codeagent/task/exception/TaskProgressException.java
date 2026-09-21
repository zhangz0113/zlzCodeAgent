package com.zlzcode.codeagent.task.exception;

public final class TaskProgressException extends RuntimeException {

    public static final String RUN_NOT_FOUND_CODE = "TASK_RUN_NOT_FOUND";
    public static final String RUN_NOT_ACTIVE_CODE = "TASK_RUN_NOT_ACTIVE";

    private final String code;

    private TaskProgressException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static TaskProgressException runNotFound() {
        return new TaskProgressException(RUN_NOT_FOUND_CODE, "Run 不存在或不属于当前 Session");
    }

    public static TaskProgressException runNotActive() {
        return new TaskProgressException(RUN_NOT_ACTIVE_CODE, "Run 已结束，不能再补充要求");
    }

    public String code() {
        return code;
    }
}

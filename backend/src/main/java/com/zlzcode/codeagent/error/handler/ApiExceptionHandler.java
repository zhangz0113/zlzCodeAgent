package com.zlzcode.codeagent.error.handler;

import com.zlzcode.codeagent.error.dto.ApiErrorDetail;
import com.zlzcode.codeagent.error.dto.ApiErrorResponse;
import com.zlzcode.codeagent.validation.RequestContractException;
import com.zlzcode.codeagent.workspace.exception.DirectoryPickerUnavailableException;
import com.zlzcode.codeagent.workspace.exception.WorkspaceRegistryException;
import com.zlzcode.codeagent.openai.exception.OpenAiIntegrationException;
import com.zlzcode.codeagent.session.exception.SessionException;
import com.zlzcode.codeagent.agent.exception.RunException;
import com.zlzcode.codeagent.agent.exception.ApprovalException;
import com.zlzcode.codeagent.task.exception.TaskProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.zlzcode.codeagent.retrieval.exception.RetrievalException.class)
    public ResponseEntity<ApiErrorResponse> handleRetrieval(com.zlzcode.codeagent.retrieval.exception.RetrievalException exception) {
        HttpStatus status = switch (exception.reason()) {
            case DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case NOT_READY, BUSY, NEEDS_PREPARE, SOURCE_CHANGED -> HttpStatus.CONFLICT;
            case DEPENDENCY_FAILED, RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BUDGET_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SCAN_FAILED, SYNC_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(WebExchangeBindException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REQUEST", "请求结构无效", false);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REQUEST", "请求结构无效", false);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiErrorResponse> handleInput(ServerWebInputException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REQUEST", "请求结构无效", false);
    }

    @ExceptionHandler(RequestContractException.class)
    public ResponseEntity<ApiErrorResponse> handleContract(RequestContractException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                exception.code(), exception.getMessage(), false);
    }

    @ExceptionHandler(DirectoryPickerUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handlePicker(DirectoryPickerUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "DIRECTORY_PICKER_UNAVAILABLE", exception.getMessage(), true);
    }

    @ExceptionHandler(WorkspaceRegistryException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkspace(WorkspaceRegistryException exception) {
        HttpStatus status = exception.code().contains("PERSISTENCE")
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(OpenAiIntegrationException.class)
    public ResponseEntity<ApiErrorResponse> handleOpenAiIntegration(OpenAiIntegrationException exception) {
        HttpStatus status = exception.httpStatus() == null
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.valueOf(exception.httpStatus());
        return response(status, exception.code(), exception.safeMessage(), exception.retryable());
    }

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<ApiErrorResponse> handleSession(SessionException exception) {
        HttpStatus status = switch (exception.code()) {
            case SessionException.NOT_FOUND_CODE -> HttpStatus.NOT_FOUND;
            case SessionException.RUN_ALREADY_EXISTS_CODE,
                    SessionException.RUN_NOT_FOUND_CODE -> HttpStatus.CONFLICT;
            case SessionException.PERSISTENCE_FAILED_CODE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(RunException.class)
    public ResponseEntity<ApiErrorResponse> handleRun(RunException exception) {
        HttpStatus status = switch (exception.code()) {
            case RunException.NOT_FOUND_CODE -> HttpStatus.NOT_FOUND;
            case RunException.IDEMPOTENCY_CONFLICT_CODE -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(ApprovalException.class)
    public ResponseEntity<ApiErrorResponse> handleApproval(ApprovalException exception) {
        HttpStatus status = switch (exception.code()) {
            case ApprovalException.NOT_FOUND_CODE -> HttpStatus.NOT_FOUND;
            case ApprovalException.STATE_CONFLICT_CODE,
                    ApprovalException.EXPIRED_CODE -> HttpStatus.CONFLICT;
            case ApprovalException.PERSISTENCE_FAILED_CODE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, exception.code(), exception.getMessage(), exception.retryable());
    }

    @ExceptionHandler(TaskProgressException.class)
    public ResponseEntity<ApiErrorResponse> handleTaskProgress(TaskProgressException exception) {
        HttpStatus status = TaskProgressException.RUN_NOT_FOUND_CODE.equals(exception.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        return response(status, exception.code(), exception.getMessage(), false);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            boolean retryable) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ApiErrorDetail(code, message, retryable)));
    }
}

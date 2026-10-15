package com.zeroziba.orderstock.common;

import com.zeroziba.orderstock.monitoring.OrderMetrics;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final OrderMetrics metrics;

    public ApiExceptionHandler(OrderMetrics metrics) { this.metrics = metrics; }

    public record ApiError(String code, String message) { }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleBusinessError(ApiException exception) {
        if (exception.getCode().equals("INSUFFICIENT_STOCK")) { metrics.insufficientStockResponse(); }
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted().findFirst().orElse("요청 값을 확인하세요.");
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadableRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "JSON 형식과 입력 값의 자료형을 확인하세요."));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleLockConflict(PessimisticLockingFailureException exception) {
        Throwable cause=exception;
        for(int i=0;i<8 && cause.getCause()!=null;i++) cause=cause.getCause();
        if(cause instanceof java.sql.SQLException sql) {
            log.warn("lock_conflict causeType={} sqlState={} vendorCode={}",cause.getClass().getSimpleName(),sql.getSQLState(),sql.getErrorCode());
        } else log.warn("lock_conflict causeType={}",cause.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ORDER_CONFLICT", "다른 주문과 충돌했습니다. 잠시 후 다시 시도하세요."));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleUnexpectedFailure(RuntimeException exception) {
        // Preserve framework-defined statuses, including method/header validation errors.
        if (exception instanceof ErrorResponse error && !error.getStatusCode().is5xxServerError()) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(new ApiError("INVALID_REQUEST", "요청 형식과 입력 값을 확인하세요."));
        }
        Throwable cause = exception;
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        String location = Arrays.stream(exception.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.zeroziba.orderstock."))
                .limit(8).map(StackTraceElement::toString).collect(Collectors.joining(","));
        // Exception messages, SQL values and Throwable rendering may contain credentials or personal data.
        log.error("unexpected_failure type={} causeType={} location={}",
                exception.getClass().getName(), cause.getClass().getName(), location);
        var response = exception instanceof ErrorResponse error
                ? ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                : ResponseEntity.internalServerError();
        return response
                .body(new ApiError("INTERNAL_ERROR", "서버에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."));
    }
}

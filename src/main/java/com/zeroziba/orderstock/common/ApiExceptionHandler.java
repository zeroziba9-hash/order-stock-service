package com.zeroziba.orderstock.common;

import com.zeroziba.orderstock.monitoring.OrderMetrics;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
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
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ORDER_CONFLICT", "다른 주문과 충돌했습니다. 잠시 후 다시 시도하세요."));
    }
}

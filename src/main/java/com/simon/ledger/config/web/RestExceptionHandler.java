package com.simon.ledger.config.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.Result;
import com.simon.ledger.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> businessExceptionHandler(BusinessException e) {
        log.warn("business exception code={}", e.getErrorCode());
        if (e.getErrorCode() == ErrorCode.SUCCESS) {
            return response(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage(), null);
        }
        return response(e.getErrorCode(), e.getMessage(), e.getData());
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<Result<?>> permissionExceptionHandler(Exception e) {
        log.warn("permission denied type={}", e.getClass().getSimpleName());
        return response(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage(), null);
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<?>> notLoginExceptionHandler(NotLoginException e) {
        log.warn("not login type={}", e.getClass().getSimpleName());
        return response(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException e) {
        String message = ErrorCode.BAD_REQUEST.getMessage();
        if (!e.getBindingResult().getAllErrors().isEmpty()) {
            message = e.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        }
        return response(ErrorCode.BAD_REQUEST, message, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<?>> constraintViolationExceptionHandler(ConstraintViolationException e) {
        return response(ErrorCode.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> exceptionHandler(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("system exception correlationId={}", correlationId);
        Result<?> result = Result.fail(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage(), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Correlation-Id", correlationId)
                .body(result);
    }

    private ResponseEntity<Result<?>> response(ErrorCode errorCode, String message, Object data) {
        Result<?> result = Result.fail(errorCode, message, data);
        return ResponseEntity.status(httpStatus(errorCode)).body(result);
    }

    private HttpStatus httpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SUCCESS -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}

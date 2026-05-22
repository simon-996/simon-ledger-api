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

@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> businessExceptionHandler(BusinessException e) {
        log.warn("business exception: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public Result<Void> permissionExceptionHandler(Exception e) {
        log.warn("permission denied: {}", e.getMessage());
        return Result.fail(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> notLoginExceptionHandler(NotLoginException e) {
        log.warn("not login: {}", e.getMessage());
        return Result.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException e) {
        String message = ErrorCode.BAD_REQUEST.getMessage();
        if (!e.getBindingResult().getAllErrors().isEmpty()) {
            message = e.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        }
        return Result.fail(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> constraintViolationExceptionHandler(ConstraintViolationException e) {
        return Result.fail(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> exceptionHandler(Exception e) {
        log.error("system exception", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}

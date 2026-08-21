package com.simon.ledger.config.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.Result;
import com.simon.ledger.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class RestExceptionHandlerTests {
    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void businessErrorsUseTheirHttpStatus() {
        assertStatus(ErrorCode.BAD_REQUEST, 400);
        assertStatus(ErrorCode.UNAUTHORIZED, 401);
        assertStatus(ErrorCode.FORBIDDEN, 403);
        assertStatus(ErrorCode.NOT_FOUND, 404);
        assertStatus(ErrorCode.CONFLICT, 409);
        assertStatus(ErrorCode.SYSTEM_ERROR, 500);
        assertStatus(ErrorCode.SUCCESS, 200);
    }

    @Test
    void conflictReturnsStructuredPayloadAndConflictStatus() throws Exception {
        Class<?> dtoType = Class.forName("com.simon.ledger.dto.resp.ConflictResp");
        Constructor<?> dtoConstructor = dtoType.getConstructor(String.class, String.class, Integer.class,
                Integer.class, Boolean.class, Object.class);
        Object conflict = dtoConstructor.newInstance("transaction", "uuid-1", 2, 3, false,
                Collections.singletonMap("amount", 10));
        Class<?> exceptionType = Class.forName("com.simon.ledger.common.exception.VersionConflictException");
        Object exception = exceptionType.getConstructor(dtoType).newInstance(conflict);
        ResponseEntity<?> response = (ResponseEntity<?>) handler.getClass()
                .getMethod("businessExceptionHandler", BusinessException.class)
                .invoke(handler, exception);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Result<?> body = (Result<?>) response.getBody();
        assertEquals(409001, body.getCode());
        assertEquals("数据已被其他设备修改", body.getMessage());
        assertSame(conflict, body.getData());
    }

    @Test
    void accessAndValidationExceptionsUseBadges() {
        assertHttpStatus(() -> handler.permissionExceptionHandler(new NotRoleException("role")), 403);
        assertHttpStatus(() -> handler.permissionExceptionHandler(new NotPermissionException("permission")), 403);
        assertHttpStatus(() -> handler.notLoginExceptionHandler(NotLoginException.newInstance("login", "token", "", "")), 401);

        var target = new Object();
        var binding = new BeanPropertyBindingResult(target, "target");
        binding.addError(new FieldError("target", "name", "name required"));
        var validation = new MethodArgumentNotValidException(null, binding);
        assertHttpStatus(() -> handler.methodArgumentNotValidExceptionHandler(validation), 400);
        assertHttpStatus(() -> handler.constraintViolationExceptionHandler(
                new ConstraintViolationException("invalid", Collections.emptySet())), 400);
    }

    @Test
    void unknownExceptionsReturnSanitizedSystemError() {
        Object response = handler.exceptionHandler(new IllegalStateException("secret should not leak"));
        assertTrue(response instanceof ResponseEntity<?>);
        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, entity.getStatusCode());
        Result<?> body = (Result<?>) entity.getBody();
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), body.getCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), body.getMessage());
        assertFalse(body.getMessage().contains("secret should not leak"));
    }

    private void assertStatus(ErrorCode code, int status) {
        assertHttpStatus(() -> handler.businessExceptionHandler(new BusinessException(code, "message")), status);
    }

    private void assertHttpStatus(ThrowingSupplier supplier, int status) {
        Object response = assertDoesNotThrow(supplier::get);
        assertTrue(response instanceof ResponseEntity<?>);
        assertEquals(status, ((ResponseEntity<?>) response).getStatusCode().value());
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}

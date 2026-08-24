package com.simon.ledger.config.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.Result;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.resp.ConflictResp;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

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
    }

    @Test
    void successCodeMisuseFailsClosedAsSystemError() {
        ResponseEntity<?> response = handler.businessExceptionHandler(new BusinessException(ErrorCode.SUCCESS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Result<?> body = (Result<?>) response.getBody();
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), body.getCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), body.getMessage());
    }

    @Test
    void conflictReturnsStructuredPayloadAndConflictStatus() {
        ConflictResp conflict = new ConflictResp("transaction", "uuid-1", 2, 3, false,
                Collections.singletonMap("amount", 10));
        ResponseEntity<?> response = handler.businessExceptionHandler(new VersionConflictException(conflict));
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
        assertBadRequestMessage(handler.methodArgumentNotValidExceptionHandler(validation), "name required");
        assertBadRequestMessage(handler.constraintViolationExceptionHandler(
                new ConstraintViolationException("invalid", Collections.emptySet())), "invalid");
    }

    @Test
    void bindingFailureDoesNotExposeRejectedValueOrConversionDetails() {
        var binding = new BeanPropertyBindingResult(new Object(), "target");
        binding.addError(new FieldError("target", "amount", "invalid-binding-secret", true,
                null, null, "conversion exception secret"));

        ResponseEntity<Result<?>> response = handler.methodArgumentNotValidExceptionHandler(
                new MethodArgumentNotValidException(null, binding));

        assertBadRequestMessage(response, ErrorCode.BAD_REQUEST.getMessage());
        assertFalse(response.getBody().getMessage().contains("invalid-binding-secret"));
        assertFalse(response.getBody().getMessage().contains("conversion exception secret"));
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
        ResponseEntity<?> response = handler.businessExceptionHandler(new BusinessException(code, "message"));
        assertNotNull(response.getBody());
        assertNull(((Result<?>) response.getBody()).getData());
    }

    private void assertHttpStatus(ThrowingSupplier supplier, int status) {
        Object response = assertDoesNotThrow(supplier::get);
        assertTrue(response instanceof ResponseEntity<?>);
        assertEquals(status, ((ResponseEntity<?>) response).getStatusCode().value());
    }

    private void assertBadRequestMessage(ResponseEntity<Result<?>> response, String message) {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
        assertEquals(message, response.getBody().getMessage());
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}

package com.simon.ledger.config.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.resp.ConflictResp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.ConstraintViolationException;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestExceptionMvcBoundaryTests {
    private final Logger logger = (Logger) LogManager.getLogger(RestExceptionHandler.class);
    private final CapturingAppender appender = new CapturingAppender();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        logger.removeAppender(appender);
    }

    @Test
    void conflictCrossesRealMvcBoundaryWithStructuredJson() throws Exception {
        mvc.perform(get("/throw-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409001))
                .andExpect(jsonPath("$.message").value("数据已被其他设备修改"))
                .andExpect(jsonPath("$.data.entityType").value("transaction"))
                .andExpect(jsonPath("$.data.entityUuid").value("uuid-1"))
                .andExpect(jsonPath("$.data.submittedVersion").value(2))
                .andExpect(jsonPath("$.data.remoteVersion").value(3))
                .andExpect(jsonPath("$.data.remoteDeleted").value(false))
                .andExpect(jsonPath("$.data.remoteSnapshot.amount").value(10));
    }

    @Test
    void unknownExceptionDoesNotLeakMessageIntoLogsOrJson() throws Exception {
        mvc.perform(get("/throw-unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500001))
                .andExpect(jsonPath("$.message").value("系统错误"));
        String logs = String.join("\n", appender.messages);
        org.junit.jupiter.api.Assertions.assertFalse(logs.contains("secret should not leak"));
    }

    @Test
    void validationCrossesRealMvcBoundaryWithSafeMessage() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(new Payload(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001))
                .andExpect(jsonPath("$.message").value("name required"));
    }

    @Test
    void allExceptionClassesCrossRealMvcBoundary() throws Exception {
        assertStatus("/bad-request", 400, 400001);
        assertStatus("/unauthorized", 401, 401001);
        assertStatus("/forbidden", 403, 403001);
        assertStatus("/not-found", 404, 404001);
        assertStatus("/system-error", 500, 500001);
        assertStatus("/not-role", 403, 403001);
        assertStatus("/not-permission", 403, 403001);
        assertStatus("/not-login", 401, 401001);
        mvc.perform(get("/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    private void assertStatus(String path, int status, int code) throws Exception {
        mvc.perform(get(path)).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/throw-conflict")
        void conflict() {
            throw new VersionConflictException(new ConflictResp("transaction", "uuid-1", 2, 3, false,
                    Collections.singletonMap("amount", 10)));
        }

        @GetMapping("/throw-unknown")
        void unknown() {
            throw new IllegalStateException("secret should not leak");
        }

        @GetMapping("/bad-request")
        void badRequest() { throw new BusinessException(ErrorCode.BAD_REQUEST); }

        @GetMapping("/unauthorized")
        void unauthorized() { throw new BusinessException(ErrorCode.UNAUTHORIZED); }

        @GetMapping("/forbidden")
        void forbidden() { throw new BusinessException(ErrorCode.FORBIDDEN); }

        @GetMapping("/not-found")
        void notFound() { throw new BusinessException(ErrorCode.NOT_FOUND); }

        @GetMapping("/system-error")
        void systemError() { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }

        @GetMapping("/not-role")
        void notRole() { throw new NotRoleException("role"); }

        @GetMapping("/not-permission")
        void notPermission() { throw new NotPermissionException("permission"); }

        @GetMapping("/not-login")
        void notLogin() { throw NotLoginException.newInstance("login", "token", "", ""); }

        @GetMapping("/constraint-violation")
        void constraintViolation() { throw new ConstraintViolationException("invalid", Collections.emptySet()); }

        @GetMapping("/throw-access")
        void access() {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {
        }
    }

    static class Payload {
        @NotBlank(message = "name required")
        private String name;

        Payload(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    static class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("test-capture", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
            start();
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}

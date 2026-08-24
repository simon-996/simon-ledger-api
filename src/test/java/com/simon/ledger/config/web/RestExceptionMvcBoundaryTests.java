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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.ConstraintViolationException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestExceptionMvcBoundaryTests {
    private final Logger logger = (Logger) LogManager.getLogger(RestExceptionHandler.class);
    private final CapturingAppender appender = new CapturingAppender();
    private Level originalLevel;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        logger.removeAppender(appender);
        logger.setLevel(originalLevel);
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
        var result = mvc.perform(get("/throw-unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500001))
                .andExpect(jsonPath("$.message").value("系统错误"))
                .andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.contains("secret should not leak"));
        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertNotNull(correlationId);
        assertFalse(correlationId.isBlank());
        assertFalse(appender.events.isEmpty());
        assertTrue(appender.events.stream().allMatch(event -> event.getThrown() == null));
        String rendered = appender.events.stream()
                .map(event -> PatternLayout.createDefaultLayout().toSerializable(event))
                .reduce("", (all, next) -> all + next);
        assertFalse(rendered.contains("secret should not leak"));
        assertTrue(appender.events.stream().anyMatch(event ->
                event.getMessage().getFormattedMessage().contains(correlationId)));
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
    void postMissingRequestBodyIsSafeBadRequest() throws Exception {
        assertSafeBadRequest(post("/parse-body").contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void deleteMissingRequestBodyIsSafeBadRequest() throws Exception {
        assertSafeBadRequest(delete("/parse-body").contentType(MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\":", "not-json-secret"})
    void malformedJsonIsSafeBadRequest(String json) throws Exception {
        assertSafeBadRequest(post("/parse-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json), "not-json-secret", "HttpMessageNotReadableException");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\":\"invalid-number-secret\",\"date\":\"2026-01-01\",\"role\":\"OWNER\"}",
            "{\"amount\":1,\"date\":\"invalid-date-secret\",\"role\":\"OWNER\"}",
            "{\"amount\":1,\"date\":\"2026-01-01\",\"role\":\"invalid-role-secret\"}"
    })
    void invalidJsonFieldTypeIsSafeBadRequest(String json) throws Exception {
        assertSafeBadRequest(post("/parse-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json), "secret", "HttpMessageNotReadableException");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "amount=invalid-number-secret&date=2026-01-01&role=OWNER",
            "amount=1&date=invalid-date-secret&role=OWNER",
            "amount=1&date=2026-01-01&role=invalid-role-secret"
    })
    void invalidRequestParameterTypeIsSafeBadRequest(String query) throws Exception {
        assertSafeBadRequest(get("/typed-params?" + query), "secret", "MethodArgumentTypeMismatchException");
    }

    @Test
    void modelBindingTypeErrorIsSafeBadRequest() throws Exception {
        assertSafeBadRequest(get("/bind?amount=invalid-binding-secret"),
                "invalid-binding-secret", "BindException");
    }

    @Test
    void bindExceptionIsSafeBadRequest() throws Exception {
        assertSafeBadRequest(get("/bind-exception"), "invalid-binding-secret", "BindException");
    }

    @Test
    void missingRequiredRequestParameterIsSafeBadRequest() throws Exception {
        assertSafeBadRequest(get("/required-param"), "MissingServletRequestParameterException");
    }

    @Test
    void handlerMethodInputValidationIsSafeBadRequest() throws Exception {
        assertSafeBadRequest(get("/method-validation?amount=0"), "HandlerMethodValidationException");
    }

    @Test
    void handlerMethodReturnValidationRemainsASystemError() throws Exception {
        mvc.perform(get("/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500001))
                .andExpect(jsonPath("$.message").value(ErrorCode.SYSTEM_ERROR.getMessage()));
    }

    @Test
    void missingPathVariableFromControllerBugRemainsASystemError() throws Exception {
        mvc.perform(get("/misconfigured-path/value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500001))
                .andExpect(jsonPath("$.message").value(ErrorCode.SYSTEM_ERROR.getMessage()));
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

    private void assertSafeBadRequest(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                      String... forbiddenFragments) throws Exception {
        int eventStart = appender.events.size();
        var result = mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001))
                .andExpect(jsonPath("$.message").value(ErrorCode.BAD_REQUEST.getMessage()))
                .andReturn();
        String responseBody = result.getResponse().getContentAsString();
        List<LogEvent> requestEvents = appender.events.subList(eventStart, appender.events.size());
        String renderedLogs = requestEvents.stream()
                .map(event -> PatternLayout.createDefaultLayout().toSerializable(event))
                .reduce("", (all, next) -> all + next);
        assertTrue(requestEvents.stream().noneMatch(event -> event.getLevel().isMoreSpecificThan(Level.ERROR)));
        for (String fragment : forbiddenFragments) {
            assertFalse(responseBody.contains(fragment));
            assertFalse(renderedLogs.contains(fragment));
        }
        assertFalse(responseBody.contains("500001"));
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

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {
        }

        @PostMapping("/parse-body")
        void parsePost(@RequestBody ParsePayload payload) {
        }

        @DeleteMapping("/parse-body")
        void parseDelete(@RequestBody ParsePayload payload) {
        }

        @GetMapping("/typed-params")
        void typedParams(@RequestParam int amount, @RequestParam LocalDate date, @RequestParam Role role) {
        }

        @GetMapping("/bind")
        void bind(@ModelAttribute BindingPayload payload) {
        }

        @GetMapping("/bind-exception")
        void bindException() throws BindException {
            var binding = new BeanPropertyBindingResult(new Object(), "target");
            binding.addError(new FieldError("target", "amount", "invalid-binding-secret", true,
                    null, null, "conversion exception secret"));
            throw new BindException(binding);
        }

        @GetMapping("/required-param")
        void requiredParam(@RequestParam String required) {
        }

        @GetMapping("/method-validation")
        void methodValidation(@RequestParam @Min(1) int amount) {
        }


        @GetMapping("/invalid-return")
        @NotNull
        String invalidReturn() {
            return null;
        }

        @GetMapping("/misconfigured-path/{id}")
        void misconfiguredPath(@PathVariable("other") String value) {
        }
    }

    record ParsePayload(int amount, LocalDate date, Role role) {
    }

    enum Role {
        OWNER
    }

    static class BindingPayload {
        private int amount;

        public int getAmount() {
            return amount;
        }

        public void setAmount(int amount) {
            this.amount = amount;
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
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("test-capture", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
            start();
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}

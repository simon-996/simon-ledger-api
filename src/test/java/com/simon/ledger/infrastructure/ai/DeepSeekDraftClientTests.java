package com.simon.ledger.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import java.util.ArrayList;
import java.util.List;

class DeepSeekDraftClientTests {
    @Test
    void untrustedProviderStatusCannotExposeUserTextOrKeysInLogs() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("x-request-id", List.of("deepseek-request-123")), (name, value) -> true));
        when(response.body()).thenReturn("{\"status\":\"private-description-and-key\"}");
        doReturn(response).when(http).send(any(), any());
        var client = new DeepSeekDraftClient(new AiProviderConfig("test-key", "deepseek-flash", "", ""),
                new ObjectMapper(), http);
        List<String> messages = new ArrayList<>();
        var appender = new AbstractAppender("privacy-test", null, PatternLayout.createDefaultLayout(), false, null) {
            @Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
        };
        Logger logger = (Logger) LogManager.getLogger(DeepSeekDraftClient.class);
        var previousLevel = logger.getLevel();
        var previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
        try {
            assertThrows(BusinessException.class, () -> client.parse("private-description-and-key",
                    ZoneId.of("Asia/Shanghai"), "CNY"));
            assertFalse(messages.isEmpty(), "test must capture provider failure log");
            String logs = String.join("\n", messages);
            assertFalse(logs.contains("private-description-and-key"));
            org.junit.jupiter.api.Assertions.assertTrue(logs.contains("status=200"));
            org.junit.jupiter.api.Assertions.assertTrue(logs.contains("requestId=deepseek-request-123"));
        } finally {
            logger.removeAppender(appender);
            logger.setAdditive(previousAdditive);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void providerConfigurationLogsPresenceWithoutLoggingSecrets() {
        List<String> messages = new ArrayList<>();
        var appender = new AbstractAppender("provider-config-privacy-test", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
        };
        Logger logger = (Logger) LogManager.getLogger(AiProviderConfig.class);
        var previousLevel = logger.getLevel();
        var previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
        try {
            new AiProviderConfig("deepseek-private-key", "deepseek-flash", "tencent-private-id", "tencent-private-key");
            String logs = String.join("\n", messages);
            org.junit.jupiter.api.Assertions.assertTrue(logs.contains("deepSeekConfigured=true"));
            org.junit.jupiter.api.Assertions.assertTrue(logs.contains("tencentAsrConfigured=true"));
            assertFalse(logs.contains("deepseek-private-key"));
            assertFalse(logs.contains("tencent-private-id"));
            assertFalse(logs.contains("tencent-private-key"));
        } finally {
            logger.removeAppender(appender);
            logger.setAdditive(previousAdditive);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
    @Test
    void ioFailureReturnsSafeErrorWithoutInterruptingRequestThread() throws Exception {
        Thread.interrupted();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("provider details must remain private"));
        DeepSeekDraftClient client = new DeepSeekDraftClient(
                new AiProviderConfig("test-key", "deepseek-flash", "", ""), new ObjectMapper(), http);
        assertThrows(BusinessException.class,
                () -> client.parse("早餐18元", ZoneId.of("Asia/Shanghai"), "CNY"));
        assertFalse(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    void unauthorizedProviderResponseUsesSafeConfigurationMessage() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn("{\"error\":{\"message\":\"secret provider detail\"}}");
        doReturn(response).when(http).send(any(), any());
        DeepSeekDraftClient client = new DeepSeekDraftClient(
                new AiProviderConfig("test-key", "deepseek-flash", "", ""), new ObjectMapper(), http);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.parse("早餐18元", ZoneId.of("Asia/Shanghai"), "CNY"));
        verify(http).send(any(), any());
        assertEquals("AI 配置无效，请检查服务端 DeepSeek API Key", exception.getMessage());
    }
}

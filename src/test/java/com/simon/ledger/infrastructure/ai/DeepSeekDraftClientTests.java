package com.simon.ledger.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekDraftClientTests {
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

package com.simon.ledger.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekDraftClientTests {
    @Test
    void ioFailureReturnsSafeErrorWithoutInterruptingRequestThread() throws Exception {
        Thread.interrupted();
        HttpClient.Builder builder = mock(HttpClient.Builder.class);
        HttpClient http = mock(HttpClient.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(http);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("provider details must remain private"));
        try (MockedStatic<HttpClient> staticClient = mockStatic(HttpClient.class)) {
            staticClient.when(HttpClient::newBuilder).thenReturn(builder);
            DeepSeekDraftClient client = new DeepSeekDraftClient(
                    new AiProviderConfig("test-key", "deepseek-flash", "", ""), new ObjectMapper());
            assertThrows(BusinessException.class,
                    () -> client.parse("早餐18元", ZoneId.of("Asia/Shanghai"), "CNY"));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}

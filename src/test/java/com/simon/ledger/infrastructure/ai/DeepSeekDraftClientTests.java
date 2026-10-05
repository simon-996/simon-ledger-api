package com.simon.ledger.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.service.impl.AiParsingContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekDraftClientTests {
    @Test
    void sendsLedgerAwareSemanticSchemaWithoutProviderPersonIds() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"completed","output":[{"type":"message","content":[
                  {"type":"output_text","text":"{\\\"entries\\\":[]}"}]}]}
                """);
        doReturn(response).when(http).send(any(), any());
        var mapper = new ObjectMapper();
        var client = new DeepSeekDraftClient(
                new AiProviderConfig("test-key", "deepseek-flash", "", ""), mapper, http);
        var context = new AiParsingContext(
                "张三昨天住宿400，大家都住", "旅行", "CNY", ZoneId.of("Asia/Shanghai"),
                LocalDate.of(2026, 10, 5),
                List.of(new AiParsingContext.PersonCandidate("p-secret", "张三", true)),
                List.of("居住", "餐饮"), List.of("工资"));

        client.parse(context);

        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        JsonNode body = mapper.readTree(readBody(request.getValue().bodyPublisher().orElseThrow()));
        JsonNode input = mapper.readTree(body.path("input").asText());
        JsonNode properties = body.at("/text/format/schema/properties/entries/items/properties");
        assertEquals("2026-10-05", input.path("referenceDate").asText());
        assertEquals("张三", input.path("people").get(0).path("name").asText());
        assertFalse(input.path("people").get(0).has("uuid"));
        assertEquals(List.of("居住", "餐饮"), mapper.convertValue(input.path("expenseCategories"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("EQUAL", input.path("defaultSplitMode").asText());
        assertFalse(body.path("store").asBoolean());
        assertTrue(properties.has("participantScope"));
        assertTrue(properties.has("excludedPersonNames"));
        assertTrue(properties.has("dateExpression"));
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
        when(response.body()).thenReturn("{\"error\":{\"message\":\"secret provider detail\"}}");
        doReturn(response).when(http).send(any(), any());
        DeepSeekDraftClient client = new DeepSeekDraftClient(
                new AiProviderConfig("test-key", "deepseek-flash", "", ""), new ObjectMapper(), http);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.parse("早餐18元", ZoneId.of("Asia/Shanghai"), "CNY"));
        verify(http).send(any(), any());
        assertEquals("AI 配置无效，请检查服务端 DeepSeek API Key", exception.getMessage());
    }

    private static String readBody(HttpRequest.BodyPublisher publisher) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }

            @Override public void onComplete() {
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}

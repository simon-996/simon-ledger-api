package com.simon.ledger.service.impl;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.infrastructure.ai.AiProviderConfig;
import com.simon.ledger.infrastructure.ai.TencentSpeechClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiTranscriptionServiceTests {
    @Mock private AiBookkeepingAccess access;
    @Mock private AiUsageLimiter limiter;
    @Mock private TencentSpeechClient client;
    private final AiProviderConfig configured = new AiProviderConfig("text", "deepseek-flash", "id", "key");

    @Test
    void chunkedAudioReadsAtMostLimitPlusOneAndKnownOversizeReadsNothing() {
        UserAccount user = new UserAccount(); user.setId(7L);
        when(access.requireAllowed("ledger-1")).thenReturn(new AiBookkeepingAccess.Context(user, null, null));
        AiTranscriptionService service = new AiTranscriptionService(access, limiter, client, configured);
        var read = new java.util.concurrent.atomic.AtomicInteger();
        var unlimited = new java.io.InputStream() { @Override public int read() { read.incrementAndGet(); return 0; } };
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> service.transcribeStream("ledger-1", unlimited, -1)).getErrorCode());
        assertEquals(AiTranscriptionService.MAX_PCM_BYTES + 1, read.get());
        var neverRead = mock(java.io.InputStream.class);
        assertThrows(BusinessException.class, () -> service.transcribeStream("ledger-1", neverRead, AiTranscriptionService.MAX_PCM_BYTES + 1));
        verifyNoInteractions(neverRead, client);
    }

    @Test
    void globalAdmissionIsBoundedAndExceptionReleasesUserPermit() {
        var actualLimiter = new AiUsageLimiter(null,30,1000);
        java.util.List<AiUsageLimiter.Permit> permits = new java.util.ArrayList<>();
        try {
            for (long user=1; user<=4; user++) permits.add(actualLimiter.acquire(user));
            assertThrows(BusinessException.class, () -> actualLimiter.acquire(5L));
        } finally { permits.forEach(AiUsageLimiter.Permit::close); }
        assertThrows(IllegalStateException.class, () -> {
            try (var permit = actualLimiter.acquire(5L)) { throw new IllegalStateException("provider failure"); }
        });
        try (var permit = actualLimiter.acquire(5L)) { org.junit.jupiter.api.Assertions.assertNotNull(permit); }
    }

    @Test
    void httpAudioIsStreamedRatherThanBufferedByRequestBodyConverter() {
        java.lang.reflect.Method method = java.util.Arrays.stream(com.simon.ledger.controller.AiBookkeepingController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("transcribe")).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.asList(method.getParameterTypes()).contains(byte[].class),
                "authorization and size limit must run before buffering audio");
    }

    @Test
    void concurrentRequestsFromSameUserAreRejectedAndPermitReleased() throws Exception {
        UserAccount user = new UserAccount(); user.setId(7L);
        when(access.requireAllowed("ledger-1")).thenReturn(new AiBookkeepingAccess.Context(user, null, null));
        AiUsageLimiter actualLimiter = spy(new AiUsageLimiter(null, 30, 1000));
        doNothing().when(actualLimiter).consume(any(), any());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.transcribe(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("provider barrier timed out");
            }
            return "ok";
        });
        AiTranscriptionService service = new AiTranscriptionService(access, actualLimiter, client, configured);
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = workers.submit(() -> service.transcribe("ledger-1", new byte[32000]));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                assertThrows(BusinessException.class, () -> service.transcribe("ledger-1", new byte[32000]));
            } finally { release.countDown(); }
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("ok", service.transcribe("ledger-1", new byte[32000]).text());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test
    void deniesBeforeCallingSpeechProvider() {
        when(access.requireAllowed("ledger-1"))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        AiTranscriptionService service = new AiTranscriptionService(access, limiter, client, configured);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.transcribe("ledger-1", new byte[32000])).getErrorCode());
        verifyNoInteractions(client);
        verify(limiter, never()).consume(any(), any());
    }

    @Test
    void rejectsEmptyOddAndOversizedPcmBeforeProvider() {
        UserAccount user = new UserAccount();
        user.setId(7L);
        when(access.requireAllowed("ledger-1"))
                .thenReturn(new AiBookkeepingAccess.Context(user, null, null));
        AiTranscriptionService service = new AiTranscriptionService(access, limiter, client, configured);
        for (byte[] audio : new byte[][] {new byte[0], new byte[3], new byte[32000 * 61]}) {
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
                    () -> service.transcribe("ledger-1", audio)).getErrorCode());
        }
        verifyNoInteractions(client);
        verify(limiter, never()).consume(any(), any());
    }

    @Test
    void validPcmReturnsOnlyTranscript() {
        UserAccount user = new UserAccount();
        user.setId(7L);
        when(access.requireAllowed("ledger-1"))
                .thenReturn(new AiBookkeepingAccess.Context(user, null, null));
        when(client.transcribe(any(byte[].class))).thenReturn("早餐花了十八元");
        AiTranscriptionService service = new AiTranscriptionService(access, limiter, client, configured);
        assertEquals("早餐花了十八元", service.transcribe("ledger-1", new byte[32000]).text());
        verify(limiter).consume("transcribe", 7L);
    }

    @Test
    void voiceCannotRunWhenTextParserIsUnconfigured() {
        UserAccount user = new UserAccount();
        user.setId(7L);
        when(access.requireAllowed("ledger-1"))
                .thenReturn(new AiBookkeepingAccess.Context(user, null, null));
        AiTranscriptionService service = new AiTranscriptionService(access, limiter, client,
                new AiProviderConfig("", "deepseek-flash", "id", "key"));
        assertEquals(ErrorCode.SYSTEM_ERROR, assertThrows(BusinessException.class,
                () -> service.transcribe("ledger-1", new byte[32000])).getErrorCode());
        verifyNoInteractions(client);
        verify(limiter, never()).consume(any(), any());
    }
}

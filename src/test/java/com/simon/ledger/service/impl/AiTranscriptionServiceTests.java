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
    void deniesBeforeCallingSpeechProvider() {
        when(access.requireAllowed("ledger-1"))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        AiTranscriptionService service = new AiTranscriptionService(access, limiter, client, configured);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.transcribe("ledger-1", new byte[32000])).getErrorCode());
        verifyNoInteractions(client, limiter);
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
        verifyNoInteractions(client, limiter);
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
        verifyNoInteractions(client, limiter);
    }
}

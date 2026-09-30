package com.simon.ledger.service.impl;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiUsageLimiterTests {
    @Test
    void rejectsWhenUserDailyCounterExceedsLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any(String.class), any(String.class)))
                .thenReturn(0L);
        AiUsageLimiter limiter = new AiUsageLimiter(redis, 30, 1000);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> limiter.consume("parse", 7L)).getErrorCode());
        verify(redis, times(1)).execute(any(RedisScript.class), any(List.class),
                any(String.class), eq("30"));
    }
}

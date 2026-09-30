package com.simon.ledger.service.impl;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class AiUsageLimiter {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; "
                    + "if n>tonumber(ARGV[2]) then return 0 else return 1 end", Long.class);
    private final StringRedisTemplate redis;
    private final int perUserLimit;
    private final int globalLimit;

    public AiUsageLimiter(StringRedisTemplate redis,
                          @Value("${ledger.ai.daily-user-limit:30}") int perUserLimit,
                          @Value("${ledger.ai.daily-global-limit:1000}") int globalLimit) {
        this.redis = redis;
        this.perUserLimit = perUserLimit;
        this.globalLimit = globalLimit;
    }

    public void consume(String operation, Long userId) {
        if (!"parse".equals(operation) && !"transcribe".equals(operation)) {
            throw new IllegalArgumentException("Unknown AI operation");
        }
        String day = LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        long ttl = Math.max(60, Duration.between(ZonedDateTime.now(ZONE),
                LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE)).getSeconds() + 60);
        check("ai:" + operation + ":" + day + ":user:" + userId, ttl, perUserLimit);
        check("ai:" + operation + ":" + day + ":global", ttl, globalLimit);
    }

    private void check(String key, long ttl, int max) {
        final Long result;
        try {
            result = redis.execute(INCREMENT, List.of(key), Long.toString(ttl), Integer.toString(max));
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 服务暂不可用，请稍后重试");
        }
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 服务暂不可用，请稍后重试");
        }
        if (result == 0L) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "今日 AI 记账次数已用完");
        }
    }
}

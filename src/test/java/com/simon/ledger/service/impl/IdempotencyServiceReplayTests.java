package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.IdempotencyRecord;
import com.simon.ledger.mapper.IdempotencyRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceReplayTests {

    @Mock private IdempotencyRecordMapper idempotencyRecordMapper;
    @Mock private Supplier<VersionMutationResp> supplier;

    @Test
    void versionMutationResponseExposesNoArgsConstructorForReplayDeserialization() {
        assertDoesNotThrow(() -> VersionMutationResp.class.getDeclaredConstructor());
    }

    @Test
    void successfulCachedVersionMutationIsDeserializedWithoutReexecutionOrWrites() {
        IdempotencyRecord cached = new IdempotencyRecord();
        cached.setId(42L);
        cached.setUserId(7L);
        cached.setRequestKey("delete-key");
        cached.setRequestMethod("DELETE");
        cached.setRequestPath("/api/ledgers/ledger-uuid");
        cached.setResponseCode(0);
        cached.setResponseBody("{\"uuid\":\"ledger-uuid\",\"version\":3,\"deleted\":true}");
        cached.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        cached.setExpiresAt(LocalDateTime.of(2026, 9, 23, 9, 0));
        when(idempotencyRecordMapper.selectOne(any())).thenReturn(cached);

        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(idempotencyRecordMapper, objectMapper);

        VersionMutationResp response;
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            response = service.execute("delete-key", "DELETE", "/api/ledgers/ledger-uuid",
                    VersionMutationResp.class, supplier);
        }

        assertEquals("ledger-uuid", response.getUuid());
        assertEquals(3, response.getVersion());
        assertEquals(true, response.getDeleted());
        verify(supplier, never()).get();
        verify(idempotencyRecordMapper, never()).insert(any(IdempotencyRecord.class));
        verify(idempotencyRecordMapper, never()).updateById(any(IdempotencyRecord.class));
    }
}

package com.simon.ledger.service.impl;

import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.infrastructure.ai.AiProviderConfig;
import com.simon.ledger.infrastructure.ai.DeepSeekDraftClient;
import com.simon.ledger.mapper.LedgerPersonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiBookkeepingServiceTests {
    @Mock private AiBookkeepingAccess access;
    @Mock private AiUsageLimiter limiter;
    @Mock private DeepSeekDraftClient provider;
    @Mock private LedgerPersonMapper people;

    @Test
    void parsesTwoDraftsWithoutWritingTransactions() {
        UserAccount user = new UserAccount();
        user.setId(7L);
        Ledger ledger = new Ledger();
        ledger.setId(5L);
        ledger.setBaseCurrencyCode("CNY");
        when(access.requireAllowed("ledger-1"))
                .thenReturn(new AiBookkeepingAccess.Context(user, ledger, null));
        when(people.selectList(any())).thenReturn(List.of());
        when(provider.parse(eq("早餐18元，午饭32元"), any(), eq("CNY"))).thenReturn("""
                {"entries":[
                  {"sourceText":"早餐18元","type":0,"amount":"18","currencyCode":"CNY"},
                  {"sourceText":"午饭32元","type":0,"amount":"32","currencyCode":"CNY"}
                ]}
                """);
        AiBookkeepingService service = new AiBookkeepingService(access, limiter, provider, people,
                new AiDraftValidator(new com.fasterxml.jackson.databind.ObjectMapper()),
                new AiProviderConfig("test-key", "deepseek-flash", "", ""));
        AiParseReq request = new AiParseReq();
        request.setText("早餐18元，午饭32元");
        request.setZone("Asia/Shanghai");
        assertEquals(2, service.parse("ledger-1", request).getEntries().size());
        verify(limiter).consume("parse", 7L);
        verify(provider).parse(eq("早餐18元，午饭32元"), any(), eq("CNY"));
    }
}

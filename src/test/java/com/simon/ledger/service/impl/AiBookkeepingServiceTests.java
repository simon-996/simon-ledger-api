package com.simon.ledger.service.impl;

import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.infrastructure.ai.AiProviderConfig;
import com.simon.ledger.infrastructure.ai.DeepSeekDraftClient;
import com.simon.ledger.mapper.LedgerPersonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
        when(provider.parse(eq("早餐18元，午饭32元"), any(), eq("CNY"), any(), any())).thenReturn("""
                {"entries":[
                  {"sourceText":"早餐18元","type":0,"amount":"18","currencyCode":"CNY"},
                  {"sourceText":"午饭32元","type":0,"amount":"32","currencyCode":"CNY"}
                ]}
                """);
        AiBookkeepingService service = new AiBookkeepingService(access, limiter, provider, people,
                new AiDraftValidator(new com.fasterxml.jackson.databind.ObjectMapper()),
                new AiParsingContextFactory(),
                new AiSemanticDraftValidator(new com.fasterxml.jackson.databind.ObjectMapper(), new AiSemanticRules()),
                new AiProviderConfig("test-key", "deepseek-flash", "", ""));
        AiParseReq request = new AiParseReq();
        request.setText("早餐18元，午饭32元");
        request.setZone("Asia/Shanghai");
        assertEquals(2, service.parse("ledger-1", request).getEntries().size());
        verify(limiter).consume("parse", 7L);
        verify(provider).parse(eq("早餐18元，午饭32元"), any(), eq("CNY"), any(), any());
    }

    @Test
    void parsesSemanticV2WithLedgerPeopleAndCategoriesBeforeConsumingQuota() {
        var user = new UserAccount();
        user.setId(7L);
        var ledger = new Ledger();
        ledger.setId(5L);
        ledger.setName("旅行");
        ledger.setBaseCurrencyCode("CNY");
        when(access.requireAllowed("ledger-1"))
                .thenReturn(new AiBookkeepingAccess.Context(user, ledger, null));
        var person = new LedgerPerson();
        person.setUuid("p-zhang");
        person.setName("张三");
        person.setLedgerId(5L);
        person.setLinkedUserId(7L);
        when(people.selectList(any())).thenReturn(List.of(person));
        String source = "张三垫付早餐18元，所有人都吃了。";
        when(provider.parse(any(AiParsingContext.class))).thenReturn("""
                {"entries":[{"sourceText":"张三垫付早餐18元，所有人都吃了。","type":0,
                "amount":"18.00","amountExpression":"18","currencyCode":"CNY",
                "categorySuggestion":"餐饮","note":"早餐费用","dateExpression":null,"happenedAt":null,
                "paymentMode":"PERSON_PAID","payerName":"张三","participantScope":"ALL",
                "personNames":[],"excludedPersonNames":[],"splitMode":"EQUAL"}]}
                """);
        AiBookkeepingService service = service(
                new AiParsingContextFactory(Clock.fixed(Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC)));
        var request = new AiParseReq();
        request.setText(source);
        request.setZone("Asia/Shanghai");
        request.setSchemaVersion(2);
        request.setExpenseCategories(List.of("餐饮"));

        var result = service.parse("ledger-1", request);

        var captured = org.mockito.ArgumentCaptor.forClass(AiParsingContext.class);
        verify(provider).parse(captured.capture());
        assertEquals("旅行", captured.getValue().ledgerName());
        assertEquals("2026-10-05", captured.getValue().referenceDate().toString());
        assertEquals(List.of("餐饮"), captured.getValue().expenseCategories());
        assertEquals("p-zhang", result.getEntries().getFirst().getPayerPersonUuid());
        assertEquals(List.of("p-zhang"), result.getEntries().getFirst().getPersonUuids());
        verify(limiter).consume("parse", 7L);
    }

    @Test
    void rejectsUnsupportedVersionWithoutConsumingQuotaOrCallingProvider() {
        when(access.requireAllowed("ledger-1")).thenReturn(context());
        var service = service(new AiParsingContextFactory());
        var request = validRequest();
        request.setSchemaVersion(3);
        assertThrows(com.simon.ledger.common.exception.BusinessException.class,
                () -> service.parse("ledger-1", request));
        verifyNoInteractions(people, limiter, provider);
    }

    @Test
    void unauthorizedRequestDoesNotReadPeople() {
        when(access.requireAllowed("ledger-1")).thenThrow(
                new com.simon.ledger.common.exception.BusinessException(
                        com.simon.ledger.common.ErrorCode.FORBIDDEN, "forbidden"));
        assertThrows(com.simon.ledger.common.exception.BusinessException.class,
                () -> service(new AiParsingContextFactory()).parse("ledger-1", validRequest()));
        verifyNoInteractions(people, limiter, provider);
    }

    private AiBookkeepingService service(AiParsingContextFactory contextFactory) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return new AiBookkeepingService(access, limiter, provider, people,
                new AiDraftValidator(mapper), contextFactory,
                new AiSemanticDraftValidator(mapper, new AiSemanticRules()),
                new AiProviderConfig("test-key", "deepseek-flash", "", ""));
    }

    private static AiParseReq validRequest() {
        var request = new AiParseReq();
        request.setText("早餐18元");
        request.setZone("Asia/Shanghai");
        return request;
    }

    private static AiBookkeepingAccess.Context context() {
        var user = new UserAccount();
        user.setId(7L);
        var ledger = new Ledger();
        ledger.setId(5L);
        ledger.setBaseCurrencyCode("CNY");
        return new AiBookkeepingAccess.Context(user, ledger, null);
    }
}

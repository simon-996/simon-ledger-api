package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiDraftValidatorTests {
    private final AiDraftValidator validator = new AiDraftValidator(new ObjectMapper());
    private final Ledger ledger = ledger();

    @Test
    void preservesMultipleDraftsWithoutGuessingAmbiguousPeople() {
        String json = """
                {"entries":[
                  {"sourceText":"早餐18元","type":0,"amount":"18.00","currencyCode":"CNY","categorySuggestion":"餐饮","personNames":["小王"]},
                  {"sourceText":"午饭32元","type":0,"amount":"32.00","currencyCode":"CNY","categorySuggestion":"餐饮","personNames":[]}
                ]}
                """;
        var result = validator.validate(json, ledger, 7L, ZoneId.of("Asia/Shanghai"),
                List.of(person("p1", "小王"), person("p2", "小王")));
        assertEquals(2, result.getEntries().size());
        assertEquals("早餐18元", result.getEntries().get(0).getSourceText());
        assertEquals(List.of("小王"), result.getEntries().get(0).getUnresolvedNames());
        assertEquals(List.of(), result.getEntries().get(0).getPersonUuids());
    }

    @Test
    void rejectsInvalidMoneyAndTooManyEntries() {
        String entry = "{\"type\":0,\"amount\":\"-1\",\"currencyCode\":\"CNY\"}";
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> validator.validate("{\"entries\":[" + entry + "]}", ledger, 7L,
                        ZoneId.of("Asia/Shanghai"), List.of())).getErrorCode());
        String many = "{\"entries\":[" + String.join(",", java.util.Collections.nCopies(11,
                "{\"type\":0,\"amount\":\"1\",\"currencyCode\":\"CNY\"}")) + "]}";
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> validator.validate(many, ledger, 7L, ZoneId.of("Asia/Shanghai"), List.of())).getErrorCode());
    }

    private static Ledger ledger() {
        Ledger value = new Ledger();
        value.setId(5L);
        value.setBaseCurrencyCode("CNY");
        return value;
    }

    private static LedgerPerson person(String uuid, String name) {
        LedgerPerson value = new LedgerPerson();
        value.setUuid(uuid);
        value.setName(name);
        return value;
    }
}

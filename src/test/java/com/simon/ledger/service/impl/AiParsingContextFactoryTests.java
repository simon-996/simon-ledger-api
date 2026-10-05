package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiParsingContextFactoryTests {
    @Test
    void freezesShanghaiReferenceDateAndUsesOnlyCurrentActivePeople() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC);
        var factory = new AiParsingContextFactory(clock);
        var ledger = ledger(5L);
        ledger.setName("旅行");
        var request = request();
        request.setSchemaVersion(2);
        request.setExpenseCategories(List.of(" 居住 ", "居住", "交通"));
        var deleted = person("gone", "赵六", 5L);
        deleted.setDeletedAt(LocalDateTime.of(2026, 10, 1, 0, 0));

        var context = factory.create(ledger, 7L, ZoneId.of(request.getZone()), request,
                List.of(person("p-zhang", "张三", 5L), deleted, person("foreign", "别的账本人员", 6L)));

        assertEquals(LocalDate.of(2026, 10, 5), context.referenceDate());
        assertEquals(List.of("居住", "交通"), context.expenseCategories());
        assertEquals(List.of("p-zhang"), context.people().stream()
                .map(AiParsingContext.PersonCandidate::uuid).toList());
        assertFalse(new ObjectMapper().valueToTree(context.providerInput()).toString().contains("p-zhang"));
    }

    @Test
    void rejectsOversizedAndMalformedContextBeforeProviderUse() {
        var factory = new AiParsingContextFactory(Clock.fixed(
                Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC));
        var ledger = ledger(5L);
        var people = new ArrayList<LedgerPerson>();
        for (int i = 0; i < 101; i++) people.add(person("p" + i, "成员" + i, 5L));
        assertBadRequest(() -> factory.create(ledger, 7L, ZoneId.of("Asia/Shanghai"), request(), people));

        var tooManyCategories = request();
        tooManyCategories.setExpenseCategories(java.util.Collections.nCopies(101, "餐饮"));
        assertBadRequest(() -> factory.create(ledger, 7L, ZoneId.of("Asia/Shanghai"), tooManyCategories, List.of()));

        var tooLongCategory = request();
        tooLongCategory.setExpenseCategories(List.of("餐".repeat(65)));
        assertBadRequest(() -> factory.create(ledger, 7L, ZoneId.of("Asia/Shanghai"), tooLongCategory, List.of()));

        var malformedPerson = person("", "张三", 5L);
        assertBadRequest(() -> factory.create(ledger, 7L, ZoneId.of("Asia/Shanghai"), request(), List.of(malformedPerson)));
    }

    private static void assertBadRequest(Runnable action) {
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BusinessException.class, action::run).getErrorCode());
    }

    private static AiParseReq request() {
        var request = new AiParseReq();
        request.setText("张三昨天垫付住宿400，所有人都用了");
        request.setZone("Asia/Shanghai");
        return request;
    }

    private static Ledger ledger(Long id) {
        var ledger = new Ledger();
        ledger.setId(id);
        ledger.setBaseCurrencyCode("CNY");
        return ledger;
    }

    private static LedgerPerson person(String uuid, String name, Long ledgerId) {
        var person = new LedgerPerson();
        person.setUuid(uuid);
        person.setName(name);
        person.setLedgerId(ledgerId);
        return person;
    }
}

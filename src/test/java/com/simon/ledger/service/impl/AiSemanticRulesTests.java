package com.simon.ledger.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSemanticRulesTests {
    private final AiSemanticRules rules = new AiSemanticRules();

    @Test
    void mapsOnlyExactNamesAndRecognizesSelfSeparately() {
        var context = new AiParsingContext("我付了住宿费", "旅行", "CNY",
                java.time.ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 5),
                List.of(new AiParsingContext.PersonCandidate("self", "张三", true),
                        new AiParsingContext.PersonCandidate("other", "小张", false)),
                List.of(), List.of());
        assertEquals(List.of("self"), rules.match("我", context).stream()
                .map(AiParsingContext.PersonCandidate::uuid).toList());
        assertEquals(List.of("other"), rules.match("小张", context).stream()
                .map(AiParsingContext.PersonCandidate::uuid).toList());
        assertEquals(List.of(), rules.match("老张", context));
    }

    @Test
    void mapsCategoriesAgainstTheCurrentLedgerAndResolvesRelativeDays() {
        var context = new AiParsingContext("昨天酒店400", "旅行", "CNY",
                java.time.ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 5), List.of(),
                List.of("居住", "餐饮", "交通"), List.of("工资"));
        assertEquals("居住", rules.category("酒店", 0, context));
        assertEquals("餐饮", rules.category("早餐", 0, context));
        assertEquals(null, rules.category("住宿", 1, context));
        assertEquals(LocalDate.of(2026, 10, 4), rules.resolveDay("昨天", context.referenceDate()));
        assertEquals(LocalDate.of(2026, 10, 3), rules.resolveDay("前天", context.referenceDate()));
        assertEquals(LocalDate.of(2026, 10, 3), rules.resolveDay("上周六", context.referenceDate()));
        assertEquals(LocalDate.of(2026, 10, 5), rules.resolveDay("2026-10-05", context.referenceDate()));
    }

    @Test
    void recognizesRepeatedPerPersonAmountsWithoutCurrencyUnits() {
        assertTrue(rules.hasUnsupportedSplitEvidence("张三承担300李四承担100"));
        assertTrue(rules.hasUnsupportedSplitEvidence("张三承担了300李四承担了100"));
        assertTrue(rules.hasUnsupportedSplitEvidence("张三付300，李四付100"));
        assertTrue(rules.hasUnsupportedSplitEvidence("张三和李四分别垫付住宿"));
        assertFalse(rules.hasUnsupportedSplitEvidence("住宿400，张三垫付，大家使用"));
    }
}

package com.simon.ledger.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAmountExpressionParserTests {
    private final AiAmountExpressionParser parser = new AiAmountExpressionParser();

    @Test
    void parsesArabicAndChineseMoneyExpressions() {
        var cases = List.of(
                List.of("400", "400"), List.of("400.50", "400.50"),
                List.of("1,200.50", "1200.50"), List.of("四百", "400"),
                List.of("四百点五", "400.5"), List.of("十二万三千", "123000"),
                List.of("一千零二十", "1020"), List.of("一万零三", "10003"));
        for (var item : cases) assertEquals(new BigDecimal(item.get(1)), parser.parse(item.get(0)), item.get(0));
    }

    @Test
    void rejectsMissingMalformedAndOverPreciseExpressions() {
        for (String value : List.of("", "2026-10-05", "400,00", "四百百", "四百点五零一", "三比一")) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(value), value);
        }
    }
}

package com.simon.ledger.service.impl;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class AiParsingContextFactory {
    private final Clock clock;

    public AiParsingContextFactory() {
        this(Clock.systemUTC());
    }

    public AiParsingContextFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public AiParsingContext create(Ledger ledger, Long userId, ZoneId zone,
                                   AiParseReq request, List<LedgerPerson> people) {
        if (ledger == null || ledger.getId() == null || zone == null || request == null) {
            throw badRequest("账本 AI 解析上下文无效");
        }
        String currency = ledger.getBaseCurrencyCode();
        if (currency == null || currency.isBlank()) throw badRequest("账本默认币种无效");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw badRequest("账本默认币种无效");
        }

        var active = (people == null ? List.<LedgerPerson>of() : people).stream()
                .filter(person -> person.getDeletedAt() == null
                        && ledger.getId().equals(person.getLedgerId()))
                .toList();
        if (active.size() > 100 || active.stream().anyMatch(person ->
                person.getUuid() == null || person.getUuid().isBlank()
                        || person.getName() == null || person.getName().isBlank()
                        || codePoints(person.getName().trim()) > 64)) {
            throw badRequest("账本人员信息不适合本期 AI 匹配，请使用手动记账");
        }

        var candidates = active.stream().map(person -> new AiParsingContext.PersonCandidate(
                person.getUuid().trim(), person.getName().trim(),
                userId != null && userId.equals(person.getLinkedUserId()))).toList();
        return new AiParsingContext(request.getText(),
                ledger.getName() == null || ledger.getName().isBlank() ? "当前账本" : ledger.getName().trim(),
                currency, zone, LocalDate.now(clock.withZone(zone)), candidates,
                categories(request.getExpenseCategories()), categories(request.getIncomeCategories()));
    }

    private static List<String> categories(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 100 || values.stream().anyMatch(value ->
                value == null || value.isBlank() || codePoints(value.trim()) > 64)) {
            throw badRequest("分类候选格式无效");
        }
        return values.stream().map(String::trim).distinct().toList();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}

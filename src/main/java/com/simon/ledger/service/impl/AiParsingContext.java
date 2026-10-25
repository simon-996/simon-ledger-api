package com.simon.ledger.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public record AiParsingContext(
        String text,
        String ledgerName,
        String currencyCode,
        ZoneId zone,
        LocalDate referenceDate,
        List<PersonCandidate> people,
        List<String> expenseCategories,
        List<String> incomeCategories) {

    public AiParsingContext {
        people = List.copyOf(people);
        expenseCategories = List.copyOf(expenseCategories);
        incomeCategories = List.copyOf(incomeCategories);
    }

    public record PersonCandidate(String uuid, String name, boolean self) {}

    public Map<String, Object> providerInput() {
        var supportedCurrencies = currencyCode.equals("CNY")
                ? List.of("CNY") : List.of("CNY", currencyCode);
        return Map.of(
                "text", text,
                "ledgerName", ledgerName,
                "defaultCurrency", currencyCode,
                "supportedCurrencies", supportedCurrencies,
                "zone", zone.getId(),
                "referenceDate", referenceDate.toString(),
                "people", people.stream()
                        .map(person -> Map.of("name", person.name(), "isSelf", person.self()))
                        .toList(),
                "expenseCategories", expenseCategories,
                "incomeCategories", incomeCategories,
                "defaultSplitMode", "EQUAL");
    }
}

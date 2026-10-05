package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.AiDraftResp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSemanticDraftValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiSemanticDraftValidator validator = new AiSemanticDraftValidator(mapper, new AiSemanticRules());

    @Test
    void fullyPrefillsTheUserAccommodationExample() throws Exception {
        var entry = entry("张三在昨天住宿花了400元，他垫付的，所有人都用上了。");
        entry.put("categorySuggestion", "住宿");
        entry.put("dateExpression", "昨天");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");

        var draft = validate(entry).getEntries().getFirst();

        assertEquals("PERSON_PAID", draft.getPaymentMode());
        assertEquals("p-zhang", draft.getPayerPersonUuid());
        assertEquals(List.of("p-zhang", "p-li", "p-wang", "p-zhao"), draft.getPersonUuids());
        assertEquals("居住", draft.getCategorySuggestion());
        assertEquals("住宿", draft.getCategoryOriginalSuggestion());
        assertEquals(LocalDate.of(2026, 10, 4).atStartOfDay(), draft.getHappenedAt());
        assertEquals("DAY", draft.getDatePrecision());
        assertEquals("DEFAULT", draft.getFieldSources().get("splitMode"));
        assertEquals("住宿费用", draft.getNote());
        assertTrue(draft.getIssues().isEmpty());
    }

    @Test
    void keepsPayerOutOfSpecifiedParticipantsUnlessTheyAreNamedAsAUser() throws Exception {
        var entry = entry("张三昨天垫付住宿400，只有李四住了。");
        entry.put("dateExpression", "昨天");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "SPECIFIED");
        entry.put("personNames", List.of("李四"));
        var draft = validate(entry).getEntries().getFirst();
        assertEquals("p-zhang", draft.getPayerPersonUuid());
        assertEquals(List.of("p-li"), draft.getPersonUuids());
        assertTrue(draft.getIssues().isEmpty());
    }

    @Test
    void flagsUnequalNamedParticipantSharesInsteadOfLeavingThemAsEqual() throws Exception {
        var entry = entry("房费400张三垫付，张三承担300李四承担100。");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "SPECIFIED");
        entry.put("personNames", List.of("张三", "李四"));

        var draft = validate(entry).getEntries().getFirst();

        assertEquals("UNSUPPORTED", draft.getSplitMode());
        assertIssue(draft, "splitMode", "UNSUPPORTED_SPLIT");
    }

    @Test
    void doesNotChooseOnePayerWhenSeveralPeoplePaidOneEntry() throws Exception {
        var entry = entry("住宿总计400，张三付300，李四付100，大家共同承担。");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");

        var draft = validate(entry).getEntries().getFirst();

        assertNull(draft.getPayerPersonUuid());
        assertIssue(draft, "payer", "PAYER_UNSPECIFIED");
        assertEquals("UNSUPPORTED", draft.getSplitMode());
        assertIssue(draft, "splitMode", "UNSUPPORTED_SPLIT");
    }

    @Test
    void keepsAnExplicitPayerWhenOtherPeopleAreMentionedEarlier() throws Exception {
        var entry = entry("张三和李四住店400张三垫付，所有人都住。");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");

        var draft = validate(entry).getEntries().getFirst();

        assertEquals("p-zhang", draft.getPayerPersonUuid());
        assertFalse(draft.getIssues().stream().anyMatch(issue ->
                issue.field().equals("payer") && issue.code().equals("PAYER_UNSPECIFIED")));
    }

    @Test
    void flagsDuplicatePayerAndDoesNotGuessSharedPoolForUnknownMode() throws Exception {
        var duplicateContext = context(List.of(
                new AiParsingContext.PersonCandidate("p1", "张三", false),
                new AiParsingContext.PersonCandidate("p2", "张三", false),
                new AiParsingContext.PersonCandidate("p-li", "李四", false)));
        var duplicate = entry("张三垫付住宿400，李四承担。");
        duplicate.put("paymentMode", "PERSON_PAID");
        duplicate.put("payerName", "张三");
        duplicate.put("participantScope", "SPECIFIED");
        duplicate.put("personNames", List.of("李四"));
        var ambiguous = validate(duplicate, duplicateContext).getEntries().getFirst();
        assertNull(ambiguous.getPayerPersonUuid());
        assertIssue(ambiguous, "payer", "PERSON_AMBIGUOUS");

        var unknown = entry("住宿400，李四承担。");
        unknown.put("participantScope", "SPECIFIED");
        unknown.put("personNames", List.of("李四"));
        var unresolved = validate(unknown).getEntries().getFirst();
        assertEquals("UNKNOWN", unresolved.getPaymentMode());
        assertNull(unresolved.getPayerPersonUuid());
        assertIssue(unresolved, "paymentMode", "PAYMENT_UNSPECIFIED");
    }

    @Test
    void resolvesAllExceptAndFlagsUnknownExcludedPeople() throws Exception {
        var entry = entry("住宿400，张三垫付，所有人都住，除了李四和小陈。");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");
        entry.put("excludedPersonNames", List.of("李四", "小陈"));
        var draft = validate(entry).getEntries().getFirst();
        assertEquals(List.of("p-zhang", "p-wang", "p-zhao"), draft.getPersonUuids());
        assertIssue(draft, "excludedParticipants", "PERSON_NOT_FOUND");
    }

    @Test
    void keepsUnmatchedCategoryAndFlagsDateCurrencyAndUnsupportedSplit() throws Exception {
        var entry = entry("周末住宿400欧元，张三垫付，大家住，按三比一分摊。");
        entry.put("categorySuggestion", "住宿");
        entry.put("currencyCode", "EUR");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");
        entry.put("splitMode", "UNSUPPORTED");
        var noAccommodationCategory = context(List.of(
                new AiParsingContext.PersonCandidate("p-zhang", "张三", true),
                new AiParsingContext.PersonCandidate("p-li", "李四", false)));
        noAccommodationCategory = new AiParsingContext(noAccommodationCategory.text(),
                noAccommodationCategory.ledgerName(), noAccommodationCategory.currencyCode(),
                noAccommodationCategory.zone(), noAccommodationCategory.referenceDate(),
                noAccommodationCategory.people(), List.of("交通", "餐饮"), noAccommodationCategory.incomeCategories());
        var draft = validate(entry, noAccommodationCategory).getEntries().getFirst();
        assertEquals("住宿", draft.getCategorySuggestion());
        assertIssue(draft, "category", "CATEGORY_UNMATCHED");
        assertIssue(draft, "currencyCode", "CURRENCY_UNSUPPORTED");
        assertIssue(draft, "happenedAt", "DATE_AMBIGUOUS");
        assertIssue(draft, "splitMode", "UNSUPPORTED_SPLIT");
        assertNull(draft.getHappenedAt());

        var wrongBaseCurrency = entry("住宿400元，张三垫付，大家住。");
        wrongBaseCurrency.put("currencyCode", "USD");
        wrongBaseCurrency.put("paymentMode", "PERSON_PAID");
        wrongBaseCurrency.put("payerName", "张三");
        wrongBaseCurrency.put("participantScope", "ALL");
        var usdLedger = new AiParsingContext("", "旅行", "USD", noAccommodationCategory.zone(),
                noAccommodationCategory.referenceDate(), noAccommodationCategory.people(),
                noAccommodationCategory.expenseCategories(), noAccommodationCategory.incomeCategories());
        var wrongCurrency = validate(wrongBaseCurrency, usdLedger).getEntries().getFirst();
        assertIssue(wrongCurrency, "currencyCode", "CONFLICTING_FIELDS");
    }

    @Test
    void turnsUnsupportedAndFutureDatesIntoReviewIssues() throws Exception {
        var unknown = entry("周末房费400，张三垫付，大家承担。");
        unknown.put("paymentMode", "PERSON_PAID");
        unknown.put("payerName", "张三");
        unknown.put("participantScope", "ALL");
        unknown.put("dateExpression", "周末");
        var ambiguous = validate(unknown).getEntries().getFirst();
        assertIssue(ambiguous, "happenedAt", "DATE_AMBIGUOUS");

        var future = entry("明天住宿400，张三垫付，全体使用。");
        future.put("paymentMode", "PERSON_PAID");
        future.put("payerName", "张三");
        future.put("participantScope", "ALL");
        future.put("dateExpression", "明天");
        var invalidDate = validate(future).getEntries().getFirst();
        assertIssue(invalidDate, "happenedAt", "DATE_INVALID");
    }

    @Test
    void rejectsMissingOrMismatchedAmountEvidenceAndMalformedEntries() throws Exception {
        var entry = entry("住宿400，张三垫付，大家住。");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");
        entry.put("amountExpression", "401");
        assertBadRequest(() -> validate(entry));

        entry.put("amountExpression", "400");
        entry.put("amount", "400.001");
        assertBadRequest(() -> validate(entry));

        var roomNumber = entry("房号400，张三垫付，大家住。");
        roomNumber.put("paymentMode", "PERSON_PAID");
        roomNumber.put("payerName", "张三");
        roomNumber.put("participantScope", "ALL");
        assertBadRequest(() -> validate(roomNumber));
        assertBadRequest(() -> validator.validate("{\"entries\":[]}", context()));
    }

    @Test
    void rejectsEntryTextThatIsNotPartOfTheSubmittedDescription() throws Exception {
        var entry = entry("早餐400");
        String provider = mapper.writeValueAsString(Map.of("entries", List.of(entry)));
        assertBadRequest(() -> validator.validate(provider, context()));
    }

    @Test
    void doesNotApplyAllParticipantsWithoutExplicitAllEvidence() throws Exception {
        var entry = entry("张三垫付住宿400，李四承担。");
        entry.put("paymentMode", "PERSON_PAID");
        entry.put("payerName", "张三");
        entry.put("participantScope", "ALL");
        entry.put("personNames", List.of("李四"));
        var draft = validate(entry).getEntries().getFirst();
        assertTrue(draft.getPersonUuids().isEmpty());
        assertIssue(draft, "participants", "CONFLICTING_FIELDS");
        assertIssue(draft, "participants", "PARTICIPANTS_UNSPECIFIED");
    }

    @Test
    void explicitlySharedPoolHasNoPayerAndIncomeHasNoPaymentRole() throws Exception {
        var expense = entry("住宿400用共同钱包支付，大家都住。");
        expense.put("paymentMode", "SHARED_POOL");
        expense.put("participantScope", "ALL");
        var shared = validate(expense).getEntries().getFirst();
        assertEquals("SHARED_POOL", shared.getPaymentMode());
        assertNull(shared.getPayerPersonUuid());
        assertFalse(shared.getIssues().stream().anyMatch(issue -> issue.field().equals("paymentMode")));

        var income = entry("工资400，李四收到。");
        income.put("type", 1);
        income.put("paymentMode", "PERSON_PAID");
        income.put("payerName", "张三");
        income.put("categorySuggestion", "工资");
        income.put("participantScope", "SPECIFIED");
        income.put("personNames", List.of("李四"));
        var received = validate(income).getEntries().getFirst();
        assertNull(received.getPayerPersonUuid());
        assertEquals("UNKNOWN", received.getPaymentMode());
        assertFalse(received.getIssues().stream().anyMatch(issue -> issue.field().equals("paymentMode")));
    }

    private AiDraftResp validate(Map<String, Object> entry) throws Exception {
        return validate(entry, context());
    }

    private AiDraftResp validate(Map<String, Object> entry, AiParsingContext context) throws Exception {
        String source = (String) entry.get("sourceText");
        var matchingContext = new AiParsingContext(source, context.ledgerName(), context.currencyCode(), context.zone(),
                context.referenceDate(), context.people(), context.expenseCategories(), context.incomeCategories());
        return validator.validate(mapper.writeValueAsString(Map.of("entries", List.of(entry))), matchingContext);
    }

    private static Map<String, Object> entry(String source) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("sourceText", source);
        entry.put("type", 0);
        entry.put("amount", "400.00");
        entry.put("amountExpression", "400");
        entry.put("currencyCode", "CNY");
        entry.put("categorySuggestion", "住宿");
        entry.put("note", "住宿费用");
        entry.put("dateExpression", null);
        entry.put("happenedAt", null);
        entry.put("paymentMode", "UNKNOWN");
        entry.put("payerName", null);
        entry.put("participantScope", "UNKNOWN");
        entry.put("personNames", new ArrayList<>());
        entry.put("excludedPersonNames", new ArrayList<>());
        entry.put("splitMode", "EQUAL");
        return entry;
    }

    private static AiParsingContext context() {
        return context(List.of(new AiParsingContext.PersonCandidate("p-zhang", "张三", true),
                new AiParsingContext.PersonCandidate("p-li", "李四", false),
                new AiParsingContext.PersonCandidate("p-wang", "王五", false),
                new AiParsingContext.PersonCandidate("p-zhao", "赵六", false)));
    }

    private static AiParsingContext context(List<AiParsingContext.PersonCandidate> people) {
        return new AiParsingContext("张三在昨天住宿花了400元，他垫付的，所有人都用上了。", "旅行", "CNY",
                ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 5), people,
                List.of("居住", "交通", "餐饮"), List.of("工资"));
    }

    private static void assertIssue(AiDraftResp.Entry draft, String field, String code) {
        assertTrue(draft.getIssues().stream().anyMatch(issue ->
                issue.field().equals(field) && issue.code().equals(code)), field + ":" + code);
    }

    private static void assertBadRequest(ThrowingAction action) {
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BusinessException.class, action::run).getErrorCode());
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}

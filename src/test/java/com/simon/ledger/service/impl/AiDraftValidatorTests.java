package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void dropsUnsupportedAndWrongTypeCategoriesButKeepsDraft() {
        for (var category : List.of("火星分类", "工资")) {
            var entry = assertDoesNotThrow(() -> validate(Map.of("categorySuggestion", category, "note", "保留备注"), List.of()));
            assertNull(entry.path("categorySuggestion").textValue());
            assertEquals("保留备注", entry.path("note").asText());
            assertEquals(0, new java.math.BigDecimal("12.00").compareTo(entry.path("amount").decimalValue()));
        }
        assertNull(validate(Map.of("type", 1, "categorySuggestion", "餐饮"), List.of())
                .path("categorySuggestion").textValue());
    }

    @Test
    void malformedOrOversizedCategoryDoesNotDiscardTheDraft() {
        for (Object category : List.of("🪐".repeat(65), 42, List.of("餐饮"))) {
            var entry = assertDoesNotThrow(() -> validate(Map.of("categorySuggestion", category, "note", "保留备注"), List.of()));
            assertNull(entry.path("categorySuggestion").textValue());
            assertEquals("保留备注", entry.path("note").asText());
        }
    }

    @Test
    void acceptsOnlySupportedIntegerTypes() {
        for (Object type : List.of(-1, 2, 0.5, "0", true, 4294967296L)) {
            assertThrows(BusinessException.class, () -> validate(Map.of("type", type), List.of()));
        }
    }

    @Test
    void exactRolesAndRepeatedNamesKeepMetadataWithoutDuplicatingLegacyIds() {
        var entry = validate(Map.of("personNames", List.of("张三", "张三"), "payerName", "张三"),
                List.of(person("p1", "张三")));
        assertEquals(1, entry.path("personUuids").size());
        assertEquals(3, entry.path("personMatches").size());
        for (var match : entry.path("personMatches")) {
            assertEquals("张三", match.path("sourceName").asText());
            assertEquals("张三", match.path("matchedName").asText());
            assertEquals("p1", match.path("personUuid").asText());
            assertFalse(match.path("approximate").asBoolean());
        }
        assertTrue(entry.path("unresolvedNames").isEmpty());
    }

    @Test
    void ignoresProviderIdentifiersAndNeverResolvesSelfViaSemanticSuggestion() {
        var entry = validate(Map.of("personUuids", List.of("p1"), "payerPersonUuid", "p1", "paymentMode", "person"),
                List.of(person("p1", "张三")));
        assertTrue(entry.path("personUuids").isEmpty());
        assertNull(entry.path("payerPersonUuid").textValue());
        assertEquals("unconfirmed", entry.path("paymentMode").asText());
        entry = validate(Map.of("personNames", List.of("我"), "personSuggestions", List.of(
                Map.of("sourceName", "我", "role", "participant", "candidateNames", List.of("张三")))),
                List.of(person("p1", "张三")));
        assertTrue(entry.path("personUuids").isEmpty());
        assertTrue(entry.path("personMatches").path(0).path("candidatePersonUuids").isEmpty());
    }

    @Test
    void resolvesUniqueFullNameHomophonesAndPreservesOriginalRoleNames() {
        var entry = validate(Map.of("personNames", List.of("章三"), "payerName", "章三"),
                List.of(person("p1", "张三")));
        assertEquals("p1", entry.path("personUuids").path(0).asText());
        assertEquals("p1", entry.path("payerPersonUuid").asText());
        assertEquals("person", entry.path("paymentMode").asText());
        var matches = entry.path("personMatches");
        assertEquals(2, matches.size());
        for (int i = 0; i < 2; i++) {
            assertEquals("章三", matches.get(i).path("sourceName").asText());
            assertEquals(i == 0 ? "participant" : "payer", matches.get(i).path("role").asText());
            assertEquals("张三", matches.get(i).path("matchedName").asText());
            assertTrue(matches.get(i).path("approximate").asBoolean());
        }
    }

    @Test
    void normalizesFullwidthAndWhitespaceOnlyForMatching() {
        var entry = validate(Map.of("personNames", List.of(" Ａ　ｌｉｃｅ ")),
                List.of(person("p1", "Alice")));
        assertEquals("p1", entry.path("personUuids").path(0).asText());
        assertEquals(" Ａ　ｌｉｃｅ ", entry.path("personMatches").path(0).path("sourceName").asText());
        assertFalse(entry.path("personMatches").path(0).path("approximate").asBoolean());
    }

    @Test
    void duplicateExactAndMultipleHomophonesStayUnresolvedWithCandidates() {
        for (var names : List.of(List.of("张三", "张三"), List.of("张三", "章三"))) {
            var entry = validate(Map.of("personNames", List.of("张三".equals(names.get(1)) ? "张三" : "张叁")),
                    List.of(person("p1", names.get(0)), person("p2", names.get(1))));
            assertTrue(entry.path("personUuids").isEmpty());
            assertEquals(1, entry.path("personMatches").size());
            var match = entry.path("personMatches").path(0);
            assertNull(match.path("personUuid").textValue());
            assertEquals(List.of("p1", "p2"), new ObjectMapper().convertValue(
                    match.path("candidatePersonUuids"), List.class));
        }
    }

    @Test
    void editsAbbreviationsAndPolyphonicOverlapAreCandidatesOnly() {
        for (var source : List.of("张四", "张", "张三丰")) {
            var entry = validate(Map.of("personNames", List.of(source)), List.of(person("p1", "张三")));
            assertTrue(entry.path("personUuids").isEmpty());
            assertEquals("p1", entry.path("personMatches").path(0).path("candidatePersonUuids").path(0).asText());
            assertTrue(entry.path("personMatches").path(0).path("approximate").asBoolean());
        }
        var entry = validate(Map.of("personNames", List.of("张悦")), List.of(person("p1", "张乐")));
        assertTrue(entry.path("personUuids").isEmpty());
        assertEquals("p1", entry.path("personMatches").path(0).path("candidatePersonUuids").path(0).asText());
    }

    @Test
    void editCandidatesRequireAtLeastTwoCodePointsInBothNames() {
        for (var names : List.of(List.of("张", "李"), List.of("张三", "三"),
                List.of("🪐", "🌞"), List.of("🪐🌞", "🌞"))) {
            var entry = validate(Map.of("personNames", List.of(names.get(0))),
                    List.of(person("p1", names.get(1))));
            assertTrue(entry.path("personUuids").isEmpty());
            var match = entry.path("personMatches").path(0);
            assertEquals(names.get(0), match.path("sourceName").asText());
            assertTrue(match.path("candidatePersonUuids").isEmpty(), "one-character edit evidence must not suggest identity");
            assertFalse(match.path("approximate").asBoolean());
        }
    }

    @Test
    void singleCharacterNamesStillAllowAbbreviationsSuggestionsAndGenuineHomophones() {
        var abbreviated = validate(Map.of("personNames", List.of("张")), List.of(person("p1", "张三")));
        assertTrue(abbreviated.path("personUuids").isEmpty());
        assertEquals("p1", abbreviated.path("personMatches").path(0).path("candidatePersonUuids").path(0).asText());

        var suggested = validate(Map.of("personNames", List.of("张"), "personSuggestions", List.of(
                Map.of("sourceName", "张", "role", "participant", "candidateNames", List.of("李")))),
                List.of(person("p1", "李")));
        assertTrue(suggested.path("personUuids").isEmpty());
        assertEquals("p1", suggested.path("personMatches").path(0).path("candidatePersonUuids").path(0).asText());

        var homophone = validate(Map.of("personNames", List.of("章")), List.of(person("p1", "张")));
        assertEquals("p1", homophone.path("personUuids").path(0).asText());
        assertEquals("p1", homophone.path("personMatches").path(0).path("personUuid").asText());
    }

    @Test
    void selfRequiresUniqueLinkageAndExcludesDeletedOrOtherLedgerPeople() {
        var deleted = person("deleted", "张三");
        deleted.setDeletedAt(LocalDateTime.now());
        deleted.setLinkedUserId(7L);
        var foreign = person("foreign", "张三");
        foreign.setLedgerId(99L);
        foreign.setLinkedUserId(7L);
        var literalSelf = person("literal", "我");
        var entry = validate(Map.of("personNames", List.of("我", "张三")), List.of(deleted, foreign, literalSelf));
        assertTrue(entry.path("personUuids").isEmpty());
        assertEquals(2, entry.path("unresolvedNames").size());
        assertTrue(entry.path("personMatches").get(0).path("candidatePersonUuids").isEmpty());
        var linked = person("linked", "李四");
        linked.setLinkedUserId(7L);
        entry = validate(Map.of("personNames", List.of("我")), List.of(deleted, foreign, linked));
        assertEquals("linked", entry.path("personUuids").get(0).asText());
        assertEquals("我", entry.path("personMatches").get(0).path("sourceName").asText());
        var linked2 = person("linked2", "王五");
        linked2.setLinkedUserId(7L);
        entry = validate(Map.of("personNames", List.of("我")), List.of(linked, linked2));
        assertTrue(entry.path("personUuids").isEmpty());
        assertEquals(2, entry.path("personMatches").get(0).path("candidatePersonUuids").size());
    }

    @Test
    void unresolvedPayerHasSeparateRoleAndWalletNeedsExplicitMode() {
        for (var mode : List.of("unknown", "person", "unconfirmed")) {
            var entry = validate(Map.of("payerName", "陌生付款人", "paymentMode", mode), List.of());
            assertEquals("unconfirmed", entry.path("paymentMode").asText());
            assertEquals("payer", entry.path("personMatches").get(0).path("role").asText());
            assertEquals("陌生付款人", entry.path("unresolvedNames").get(0).asText());
        }
        assertEquals("unconfirmed", validate(Map.of("note", "公共钱包支付"), List.of()).path("paymentMode").asText());
        assertEquals("shared_wallet", validate(Map.of("paymentMode", "shared_wallet"), List.of()).path("paymentMode").asText());
        assertEquals("person", validate(Map.of("payerName", "张三", "paymentMode", "shared_wallet"),
                List.of(person("p1", "张三"))).path("paymentMode").asText());
    }

    @Test
    void semanticAndShapeSuggestionsOnlySupplyValidLedgerCandidates() {
        var foreign = person("foreign", "外部成员");
        foreign.setLedgerId(99L);
        var entry = validate(Map.of("personNames", List.of("爸爸"), "payerName", "爸爸",
                "personSuggestions", List.of(Map.of("sourceName", "爸爸", "role", "participant",
                        "candidateNames", List.of("王建国", "外部成员", "编造成员")))),
                List.of(person("p1", "王建国"), foreign));
        assertTrue(entry.path("personUuids").isEmpty());
        assertEquals("p1", entry.path("personMatches").path(0).path("candidatePersonUuids").path(0).asText());
        assertTrue(entry.path("personMatches").path(1).path("candidatePersonUuids").isEmpty());
    }

    private com.fasterxml.jackson.databind.JsonNode validate(Map<String, Object> fields, List<LedgerPerson> people) {
        try {
            var values = new java.util.HashMap<String, Object>(Map.of("type", 0, "amount", "12", "currencyCode", "CNY"));
            values.putAll(fields);
            var mapper = new ObjectMapper();
            var response = validator.validate(mapper.writeValueAsString(Map.of("entries", List.of(values))),
                    ledger, 7L, ZoneId.of("Asia/Shanghai"), people);
            return mapper.valueToTree(response).path("entries").get(0);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
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
        value.setLedgerId(5L);
        return value;
    }
}

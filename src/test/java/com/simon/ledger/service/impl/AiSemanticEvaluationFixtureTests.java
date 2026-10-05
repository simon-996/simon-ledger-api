package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSemanticEvaluationFixtureTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void containsAtLeastSixtyUniqueCasesWithIndividualExpectedResults() throws Exception {
        JsonNode cases = readCases();
        assertTrue(cases.size() >= 60, "fixture corpus should contain at least 60 cases");

        Set<String> ids = new HashSet<>();
        Set<String> issueCodes = new HashSet<>();
        Set<String> scenarios = new HashSet<>();
        for (JsonNode fixture : cases) {
            String id = fixture.path("id").asText();
            assertFalse(id.isBlank(), "fixture id is required");
            assertTrue(ids.add(id), "duplicate fixture id: " + id);
            assertFalse(fixture.path("text").asText().isBlank(), id + " text is required");
            assertTrue(fixture.path("expected").isObject(), id + " needs its own expected object");
            assertTrue(fixture.path("expected").has("issueCodes")
                            || fixture.path("expected").has("failure"),
                    id + " needs expected issue codes or an expected parse failure");

            JsonNode context = fixture.path("context");
            assertEquals("2026-10-05", context.path("referenceDate").asText(), id);
            assertEquals("Asia/Shanghai", context.path("zone").asText(), id);
            assertTrue(context.path("people").isArray(), id + " people should be explicit");
            assertTrue(context.path("expenseCategories").isArray(), id + " categories should be explicit");
            fixture.path("expected").path("issueCodes").forEach(code -> issueCodes.add(code.asText()));
            scenarios.add(fixture.path("scenario").asText());

            JsonNode expectedAmount = fixture.path("expected").path("amount");
            if (expectedAmount.isTextual()) {
                assertTrue(new BigDecimal(expectedAmount.asText()).signum() > 0, id);
            }
            JsonNode expectedPeople = fixture.path("expected").path("personUuids");
            assertTrue(expectedPeople.isArray(), id + " expected participant IDs should be explicit");
        }

        assertTrue(ids.contains("A01-01"));
        assertTrue(ids.contains("A36-04"));
        assertTrue(issueCodes.contains("PAYMENT_UNSPECIFIED"));
        assertTrue(issueCodes.contains("PERSON_AMBIGUOUS"));
        assertTrue(issueCodes.contains("PARTICIPANTS_UNSPECIFIED"));
        assertTrue(issueCodes.contains("UNSUPPORTED_SPLIT"));
        assertTrue(issueCodes.contains("DATE_INVALID"));
        assertTrue(issueCodes.contains("CURRENCY_UNSUPPORTED"));
        assertTrue(scenarios.contains("provider-reject"));
        assertTrue(scenarios.contains("prompt-injection-is-data"));
    }

    @Test
    void keepsImportantContextVariantsAndExpectedFailureCasesExplicit() throws Exception {
        JsonNode cases = readCases();
        JsonNode accommodationCategory = find(cases, "A02-01");
        assertTrue(contains(accommodationCategory.path("context").path("expenseCategories"), "住宿"));
        JsonNode semanticCategory = find(cases, "A03-01");
        assertEquals(1, semanticCategory.path("context").path("expenseCategories").size());
        assertEquals("居住", semanticCategory.path("expected").path("categorySuggestion").asText());

        JsonNode duplicateName = find(cases, "A08-01");
        assertEquals(2, countNamed(duplicateName.path("context").path("people"), "张三"));
        JsonNode noSelf = find(cases, "A10-01");
        assertEquals(0, countSelf(noSelf.path("context").path("people")));
        JsonNode multipleSelf = find(cases, "A10-02");
        assertEquals(2, countSelf(multipleSelf.path("context").path("people")));
        assertEquals("USD", find(cases, "A21-01").path("context").path("currencyCode").asText());
        assertEquals("CNY", find(cases, "A22-01").path("context").path("currencyCode").asText());
        assertEquals("MISSING_AMOUNT", find(cases, "A26-01").path("expected").path("failure").asText());
        assertEquals("TOO_MANY_ENTRIES", find(cases, "A36-04").path("expected").path("failure").asText());
        assertEquals("UNKNOWN", find(cases, "A23-02").path("expected").path("participantScope").asText());
        assertTrue(contains(find(cases, "A23-02").path("expected").path("issueCodes"),
                "PARTICIPANTS_UNSPECIFIED"));
        assertTrue(find(cases, "A24-01").path("expected").path("payerPersonUuid").isNull());
        assertTrue(contains(find(cases, "A24-02").path("expected").path("issueCodes"),
                "PAYER_UNSPECIFIED"));
        assertTrue(find(cases, "A25-01").path("expected").path("entries").size() >= 3);
        assertTrue(find(cases, "A25-02").path("expected").path("entries").size() >= 2);
    }

    private JsonNode readCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/ai/semantic-prefill-cases.json");
        assertTrue(stream != null, "semantic fixture resource should exist");
        try (stream) {
            return mapper.readTree(stream);
        }
    }

    private JsonNode find(JsonNode cases, String id) {
        for (JsonNode fixture : cases) {
            if (id.equals(fixture.path("id").asText())) return fixture;
        }
        throw new AssertionError("missing fixture: " + id);
    }

    private boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private long countNamed(JsonNode people, String name) {
        long count = 0;
        for (JsonNode person : people) if (name.equals(person.path("name").asText())) count++;
        return count;
    }

    private long countSelf(JsonNode people) {
        long count = 0;
        for (JsonNode person : people) if (person.path("self").asBoolean()) count++;
        return count;
    }
}

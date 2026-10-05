package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AiDraftResp {
    private List<Entry> entries = new ArrayList<>();

    public record Issue(String id, String field, String code,
                        String sourceText, List<String> candidateUuids) {}

    @Data
    public static class Entry {
        private Integer schemaVersion = 1;
        private String sourceText;
        private Integer type;
        private BigDecimal amount;
        private String currencyCode;
        private String categorySuggestion;
        private String note;
        private LocalDateTime happenedAt;
        private String paymentMode = "UNKNOWN";
        private String participantScope = "UNKNOWN";
        private String splitMode = "EQUAL";
        private String datePrecision = "DAY";
        private String categoryOriginalSuggestion;
        private String referenceDate;
        private String referenceZone;
        private Map<String, String> fieldSources = new LinkedHashMap<>();
        private List<Issue> issues = new ArrayList<>();
        private String payerPersonUuid;
        private List<String> personUuids = new ArrayList<>();
        private List<String> unresolvedNames = new ArrayList<>();
        private List<PersonMatch> personMatches = new ArrayList<>();
    }

    @Data
    public static class PersonMatch {
        private String sourceName;
        private String role;
        private String personUuid;
        private String matchedName;
        private boolean approximate;
        private List<String> candidatePersonUuids = new ArrayList<>();
    }
}

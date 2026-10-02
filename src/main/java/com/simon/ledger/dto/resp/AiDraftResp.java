package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiDraftResp {
    private List<Entry> entries = new ArrayList<>();

    @Data
    public static class Entry {
        private String sourceText;
        private Integer type;
        private BigDecimal amount;
        private String currencyCode;
        private String categorySuggestion;
        private String note;
        private LocalDateTime happenedAt;
        private String payerPersonUuid;
        private List<String> personUuids = new ArrayList<>();
        private List<String> unresolvedNames = new ArrayList<>();
        private String paymentMode = "unconfirmed";
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

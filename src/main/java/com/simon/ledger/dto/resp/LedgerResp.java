package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class LedgerResp {

    private String uuid;

    private String name;

    private String baseCurrencyCode;

    private BigDecimal exchangeRateToCny;

    private String role;

    private Integer memberCount;

    private List<LedgerMemberSummaryResp> members;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

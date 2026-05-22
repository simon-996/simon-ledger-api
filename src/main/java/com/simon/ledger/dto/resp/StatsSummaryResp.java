package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StatsSummaryResp {

    private String ledgerUuid;

    private BigDecimal expense;

    private BigDecimal income;

    private BigDecimal balance;

    private long transactionCount;
}

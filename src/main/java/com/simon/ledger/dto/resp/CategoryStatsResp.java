package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CategoryStatsResp {

    private String category;

    private Integer type;

    private BigDecimal amount;

    private long transactionCount;
}

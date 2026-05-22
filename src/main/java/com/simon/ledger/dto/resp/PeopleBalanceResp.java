package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PeopleBalanceResp {

    private String personUuid;

    private String name;

    private String avatar;

    private BigDecimal expense;

    private BigDecimal income;

    private BigDecimal balance;
}

package com.simon.ledger.dto.resp;

import lombok.Data;

@Data
public class AdminLoginResp {

    private String tokenName;

    private String tokenValue;

    private AdminUserResp user;
}

package com.simon.ledger.dto.resp;

import lombok.Data;

@Data
public class AuthLoginResp {

    private String tokenName;

    private String tokenValue;

    private AuthUserResp user;
}

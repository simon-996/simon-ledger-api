package com.simon.ledger.dto.resp;

import lombok.Data;

@Data
public class AdminUserResp {

    private String uuid;

    private String account;

    private String nickname;

    private String role;

    private Integer status;
}

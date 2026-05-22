package com.simon.ledger.dto.resp;

import lombok.Data;

@Data
public class AuthUserResp {

    private String uuid;

    private String email;

    private String phone;

    private String nickname;

    private String avatar;

    private Integer status;
}

package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemberResp {

    private String uuid;

    private String userUuid;

    private String nickname;

    private String avatar;

    private String role;

    private Integer status;

    private LocalDateTime joinedAt;
}

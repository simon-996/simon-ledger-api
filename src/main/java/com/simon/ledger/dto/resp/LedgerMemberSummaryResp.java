package com.simon.ledger.dto.resp;

import lombok.Data;

@Data
public class LedgerMemberSummaryResp {

    private String uuid;

    private String userUuid;

    private String nickname;

    private String avatar;

    private String role;
}

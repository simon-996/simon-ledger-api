package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserRecordResp {

    private String uuid;

    private String nickname;

    private String account;

    private Integer status;

    private Long ledgerCount;

    private Long joinedCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

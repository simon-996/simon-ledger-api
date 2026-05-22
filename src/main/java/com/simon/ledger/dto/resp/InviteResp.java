package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InviteResp {

    private String uuid;

    private String code;

    private String ledgerUuid;

    private String ledgerName;

    private String role;

    private Integer maxUses;

    private Integer usedCount;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private boolean expired;

    private boolean disabled;
}

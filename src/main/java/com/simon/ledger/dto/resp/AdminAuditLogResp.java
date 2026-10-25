package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminAuditLogResp {

    private String uuid;

    private String actor;

    private String action;

    private String target;

    private String targetUuid;

    private String targetAccount;

    private Boolean previousValue;

    private Boolean newValue;

    private String level;

    private LocalDateTime createdAt;
}

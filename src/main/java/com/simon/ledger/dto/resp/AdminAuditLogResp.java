package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminAuditLogResp {

    private String uuid;

    private String actor;

    private String action;

    private String target;

    private String level;

    private LocalDateTime createdAt;
}

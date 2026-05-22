package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChangeLogResp {

    private String uuid;

    private String ledgerUuid;

    private String entityType;

    private String entityUuid;

    private String operation;

    private String operatorUserUuid;

    private Integer version;

    private LocalDateTime createdAt;
}

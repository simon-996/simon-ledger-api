package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminLedgerRecordResp {

    private String uuid;

    private String name;

    private String owner;

    private String ownerUserUuid;

    private Integer memberCount;

    private Integer personCount;

    private Integer transactionCount;

    private String status;

    private LocalDateTime updatedAt;
}

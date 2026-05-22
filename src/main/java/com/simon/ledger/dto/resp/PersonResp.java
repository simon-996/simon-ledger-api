package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PersonResp {

    private String uuid;

    private String ledgerUuid;

    private String linkedUserUuid;

    private String name;

    private String avatar;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

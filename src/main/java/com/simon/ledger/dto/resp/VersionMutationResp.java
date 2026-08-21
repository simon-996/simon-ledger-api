package com.simon.ledger.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VersionMutationResp {
    private String uuid;
    private Integer version;
    private Boolean deleted;
}

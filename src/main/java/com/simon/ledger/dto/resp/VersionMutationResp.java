package com.simon.ledger.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VersionMutationResp {
    private String uuid;
    private Integer version;
    private Boolean deleted;
}

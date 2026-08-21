package com.simon.ledger.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConflictResp {
    private String entityType;
    private String entityUuid;
    private Integer submittedVersion;
    private Integer remoteVersion;
    private Boolean remoteDeleted;
    private Object remoteSnapshot;
}

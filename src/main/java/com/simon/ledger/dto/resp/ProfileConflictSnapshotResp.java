package com.simon.ledger.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProfileConflictSnapshotResp {
    private String uuid;
    private String nickname;
    private String avatar;
    private Integer version;
}

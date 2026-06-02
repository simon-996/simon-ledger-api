package com.simon.ledger.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class InviteResp {

    private String uuid;

    private String code;

    private String ledgerUuid;

    private String ledgerName;

    private String ledgerBaseCurrencyCode;

    private Integer ledgerMemberCount;

    private List<InviteMemberSummaryResp> ledgerMembers;

    private String role;

    private Integer maxUses;

    private Integer usedCount;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private boolean expired;

    private boolean disabled;
}

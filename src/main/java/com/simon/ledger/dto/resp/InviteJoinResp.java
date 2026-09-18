package com.simon.ledger.dto.resp;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class InviteJoinResp extends InviteResp {

    private InviteResp invite;

    private LedgerResp ledger;

    private MemberResp member;

    private PersonResp person;
}

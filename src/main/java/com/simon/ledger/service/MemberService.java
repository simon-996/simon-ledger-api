package com.simon.ledger.service;

import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.resp.MemberResp;

import java.util.List;

public interface MemberService {

    List<MemberResp> list(String ledgerUuid);

    MemberResp updateRole(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req);

    void remove(String ledgerUuid, String memberUuid);
}

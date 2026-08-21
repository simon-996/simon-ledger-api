package com.simon.ledger.service;

import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.VersionMutationResp;

import java.util.List;

public interface MemberService {

    List<MemberResp> list(String ledgerUuid);

    MemberResp updateRole(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req);

    VersionMutationResp remove(String ledgerUuid, String memberUuid, VersionDeleteReq req);

    MemberResp restore(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req);
}

package com.simon.ledger.service;

import com.simon.ledger.dto.req.InviteCreateReq;
import com.simon.ledger.dto.resp.InviteResp;

public interface InviteService {

    InviteResp create(String ledgerUuid, InviteCreateReq req);

    InviteResp getByCode(String code);

    InviteResp join(String code);
}

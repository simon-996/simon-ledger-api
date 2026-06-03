package com.simon.ledger.service;

import com.simon.ledger.dto.req.InviteCreateReq;
import com.simon.ledger.dto.req.InviteRegenerateReq;
import com.simon.ledger.dto.resp.InviteResp;

public interface InviteService {

    InviteResp create(String ledgerUuid, InviteCreateReq req);

    InviteResp current(String ledgerUuid);

    InviteResp regenerate(String ledgerUuid, InviteRegenerateReq req);

    InviteResp getByCode(String code);

    InviteResp join(String code);
}

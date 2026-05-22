package com.simon.ledger.service;

import com.simon.ledger.dto.req.AuthLoginReq;
import com.simon.ledger.dto.req.AuthRegisterReq;
import com.simon.ledger.dto.resp.AuthLoginResp;
import com.simon.ledger.dto.resp.AuthUserResp;

public interface AuthService {

    AuthUserResp register(AuthRegisterReq req);

    AuthLoginResp login(AuthLoginReq req);

    void logout();

    AuthUserResp me();
}

package com.simon.ledger.service;

import com.simon.ledger.dto.req.LedgerCreateReq;
import com.simon.ledger.dto.req.LedgerCreateWithPeopleReq;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.resp.LedgerCreateWithPeopleResp;
import com.simon.ledger.dto.resp.LedgerResp;

import java.util.List;

public interface LedgerService {

    List<LedgerResp> listMine();

    LedgerResp create(LedgerCreateReq req);

    LedgerCreateWithPeopleResp createWithPeople(LedgerCreateWithPeopleReq req);

    LedgerResp detail(String ledgerUuid);

    LedgerResp update(String ledgerUuid, LedgerUpdateReq req);

    void delete(String ledgerUuid);
}

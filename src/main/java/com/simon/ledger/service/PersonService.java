package com.simon.ledger.service;

import com.simon.ledger.dto.req.PersonCreateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.resp.PersonResp;

import java.util.List;

public interface PersonService {

    List<PersonResp> list(String ledgerUuid);

    PersonResp create(String ledgerUuid, PersonCreateReq req);

    PersonResp update(String ledgerUuid, String personUuid, PersonUpdateReq req);

    void delete(String ledgerUuid, String personUuid);
}

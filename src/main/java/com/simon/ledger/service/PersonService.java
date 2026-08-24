package com.simon.ledger.service;

import com.simon.ledger.dto.req.PersonCreateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.dto.resp.VersionMutationResp;

import java.util.List;
import java.util.Map;

public interface PersonService {

    List<PersonResp> list(String ledgerUuid);

    Map<String, List<PersonResp>> batchList(String ledgerUuids);

    PersonResp create(String ledgerUuid, PersonCreateReq req);

    PersonResp update(String ledgerUuid, String personUuid, PersonUpdateReq req);

    VersionMutationResp delete(String ledgerUuid, String personUuid, VersionDeleteReq req);

    PersonResp restore(String ledgerUuid, String personUuid, PersonUpdateReq req);
}

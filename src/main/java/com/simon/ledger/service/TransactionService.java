package com.simon.ledger.service;

import com.simon.ledger.dto.req.TransactionCreateReq;
import com.simon.ledger.dto.req.TransactionListReq;
import com.simon.ledger.dto.req.TransactionUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.PageResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.dto.resp.VersionMutationResp;

public interface TransactionService {

    PageResp<TransactionResp> list(String ledgerUuid, TransactionListReq req);

    TransactionResp create(String ledgerUuid, TransactionCreateReq req);

    TransactionResp detail(String ledgerUuid, String transactionUuid);

    TransactionResp update(String ledgerUuid, String transactionUuid, TransactionUpdateReq req);

    VersionMutationResp delete(String ledgerUuid, String transactionUuid, VersionDeleteReq req);

    TransactionResp restore(String ledgerUuid, String transactionUuid, TransactionUpdateReq req);
}

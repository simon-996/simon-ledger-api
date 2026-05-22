package com.simon.ledger.service;

import com.simon.ledger.dto.resp.ChangeLogResp;

import java.util.List;

public interface ChangeLogService {

    void record(Long ledgerId, String entityType, String entityUuid, String operation, Long operatorUserId);

    List<ChangeLogResp> changes(String ledgerUuid, Integer afterVersion);
}

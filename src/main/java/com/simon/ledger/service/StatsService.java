package com.simon.ledger.service;

import com.simon.ledger.dto.resp.CategoryStatsResp;
import com.simon.ledger.dto.resp.PeopleBalanceResp;
import com.simon.ledger.dto.resp.StatsSummaryResp;

import java.util.List;

public interface StatsService {

    StatsSummaryResp summary(String ledgerUuid);

    List<CategoryStatsResp> categories(String ledgerUuid);

    List<PeopleBalanceResp> peopleBalances(String ledgerUuid);
}

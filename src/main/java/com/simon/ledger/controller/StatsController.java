package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.resp.CategoryStatsResp;
import com.simon.ledger.dto.resp.PeopleBalanceResp;
import com.simon.ledger.dto.resp.StatsSummaryResp;
import com.simon.ledger.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Stats")
@RestController
@RequestMapping("/api/ledgers/{ledgerUuid}/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "账本汇总")
    @GetMapping("/summary")
    public Result<StatsSummaryResp> summary(@PathVariable String ledgerUuid) {
        return Result.ok(statsService.summary(ledgerUuid));
    }

    @Operation(summary = "分类统计")
    @GetMapping("/categories")
    public Result<List<CategoryStatsResp>> categories(@PathVariable String ledgerUuid) {
        return Result.ok(statsService.categories(ledgerUuid));
    }

    @Operation(summary = "人员结余")
    @GetMapping("/people-balances")
    public Result<List<PeopleBalanceResp>> peopleBalances(@PathVariable String ledgerUuid) {
        return Result.ok(statsService.peopleBalances(ledgerUuid));
    }
}

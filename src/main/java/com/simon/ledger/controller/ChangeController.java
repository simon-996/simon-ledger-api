package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.resp.ChangeLogResp;
import com.simon.ledger.service.ChangeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Sync")
@RestController
@RequestMapping("/api/ledgers/{ledgerUuid}/changes")
@RequiredArgsConstructor
public class ChangeController {

    private final ChangeLogService changeLogService;

    @Operation(summary = "增量变更")
    @GetMapping
    public Result<List<ChangeLogResp>> changes(
            @PathVariable String ledgerUuid,
            @RequestParam(defaultValue = "0") Integer afterVersion
    ) {
        return Result.ok(changeLogService.changes(ledgerUuid, afterVersion));
    }
}

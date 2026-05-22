package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.LedgerCreateReq;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Ledger")
@RestController
@RequestMapping("/api/ledgers")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @Operation(summary = "我的账本列表")
    @GetMapping
    public Result<List<LedgerResp>> listMine() {
        return Result.ok(ledgerService.listMine());
    }

    @Operation(summary = "创建账本")
    @PostMapping
    public Result<LedgerResp> create(@Valid @RequestBody LedgerCreateReq req) {
        return Result.ok(ledgerService.create(req));
    }

    @Operation(summary = "账本详情")
    @GetMapping("/{ledgerUuid}")
    public Result<LedgerResp> detail(@PathVariable String ledgerUuid) {
        return Result.ok(ledgerService.detail(ledgerUuid));
    }

    @Operation(summary = "编辑账本")
    @PutMapping("/{ledgerUuid}")
    public Result<LedgerResp> update(@PathVariable String ledgerUuid, @Valid @RequestBody LedgerUpdateReq req) {
        return Result.ok(ledgerService.update(ledgerUuid, req));
    }

    @Operation(summary = "删除账本")
    @DeleteMapping("/{ledgerUuid}")
    public Result<Void> delete(@PathVariable String ledgerUuid) {
        ledgerService.delete(ledgerUuid);
        return Result.ok();
    }
}

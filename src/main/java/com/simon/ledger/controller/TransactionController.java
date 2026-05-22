package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.TransactionCreateReq;
import com.simon.ledger.dto.req.TransactionDeleteReq;
import com.simon.ledger.dto.req.TransactionListReq;
import com.simon.ledger.dto.req.TransactionUpdateReq;
import com.simon.ledger.dto.resp.PageResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transaction")
@RestController
@RequestMapping("/api/ledgers/{ledgerUuid}/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "流水列表")
    @GetMapping
    public Result<PageResp<TransactionResp>> list(
            @PathVariable String ledgerUuid,
            @ModelAttribute TransactionListReq req
    ) {
        return Result.ok(transactionService.list(ledgerUuid, req));
    }

    @Operation(summary = "新增流水")
    @PostMapping
    public Result<TransactionResp> create(
            @PathVariable String ledgerUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionCreateReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "POST",
                "/api/ledgers/" + ledgerUuid + "/transactions",
                TransactionResp.class,
                () -> transactionService.create(ledgerUuid, req)
        ));
    }

    @Operation(summary = "流水详情")
    @GetMapping("/{transactionUuid}")
    public Result<TransactionResp> detail(
            @PathVariable String ledgerUuid,
            @PathVariable String transactionUuid
    ) {
        return Result.ok(transactionService.detail(ledgerUuid, transactionUuid));
    }

    @Operation(summary = "编辑流水")
    @PutMapping("/{transactionUuid}")
    public Result<TransactionResp> update(
            @PathVariable String ledgerUuid,
            @PathVariable String transactionUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionUpdateReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "PUT",
                "/api/ledgers/" + ledgerUuid + "/transactions/" + transactionUuid,
                TransactionResp.class,
                () -> transactionService.update(ledgerUuid, transactionUuid, req)
        ));
    }

    @Operation(summary = "删除流水")
    @DeleteMapping("/{transactionUuid}")
    public Result<Void> delete(
            @PathVariable String ledgerUuid,
            @PathVariable String transactionUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionDeleteReq req
    ) {
        idempotencyService.executeVoid(
                idempotencyKey,
                "DELETE",
                "/api/ledgers/" + ledgerUuid + "/transactions/" + transactionUuid,
                () -> transactionService.delete(ledgerUuid, transactionUuid, req)
        );
        return Result.ok();
    }
}

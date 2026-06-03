package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.InviteCreateReq;
import com.simon.ledger.dto.req.InviteRegenerateReq;
import com.simon.ledger.dto.resp.InviteResp;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.InviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invite")
@RestController
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "创建邀请")
    @PostMapping("/api/ledgers/{ledgerUuid}/invites")
    public Result<InviteResp> create(
            @PathVariable String ledgerUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InviteCreateReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "POST",
                "/api/ledgers/" + ledgerUuid + "/invites",
                InviteResp.class,
                () -> inviteService.create(ledgerUuid, req)
        ));
    }

    @Operation(summary = "查询当前可用邀请")
    @GetMapping("/api/ledgers/{ledgerUuid}/invites/current")
    public Result<InviteResp> current(@PathVariable String ledgerUuid) {
        return Result.ok(inviteService.current(ledgerUuid));
    }

    @Operation(summary = "重新生成邀请")
    @PostMapping("/api/ledgers/{ledgerUuid}/invites/regenerate")
    public Result<InviteResp> regenerate(
            @PathVariable String ledgerUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InviteRegenerateReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "POST",
                "/api/ledgers/" + ledgerUuid + "/invites/regenerate",
                InviteResp.class,
                () -> inviteService.regenerate(ledgerUuid, req)
        ));
    }

    @Operation(summary = "查询邀请")
    @GetMapping("/api/invites/{code}")
    public Result<InviteResp> getByCode(@PathVariable String code) {
        return Result.ok(inviteService.getByCode(code));
    }

    @Operation(summary = "加入账本")
    @PostMapping("/api/invites/{code}/join")
    public Result<InviteResp> join(
            @PathVariable String code,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "POST",
                "/api/invites/" + code + "/join",
                InviteResp.class,
                () -> inviteService.join(code)
        ));
    }
}

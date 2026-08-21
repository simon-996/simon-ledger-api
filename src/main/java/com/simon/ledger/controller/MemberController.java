package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.MemberService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Member")
@RestController
@RequestMapping("/api/ledgers/{ledgerUuid}/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "成员列表")
    @GetMapping
    public Result<List<MemberResp>> list(@PathVariable String ledgerUuid) {
        return Result.ok(memberService.list(ledgerUuid));
    }

    @Operation(summary = "修改成员角色")
    @PutMapping("/{memberUuid}/role")
    public Result<MemberResp> updateRole(
            @PathVariable String ledgerUuid,
            @PathVariable String memberUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MemberRoleUpdateReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "PUT",
                "/api/ledgers/" + ledgerUuid + "/members/" + memberUuid + "/role",
                MemberResp.class,
                () -> memberService.updateRole(ledgerUuid, memberUuid, req)
        ));
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{memberUuid}")
    public Result<VersionMutationResp> remove(
            @PathVariable String ledgerUuid,
            @PathVariable String memberUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VersionDeleteReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "DELETE",
                "/api/ledgers/" + ledgerUuid + "/members/" + memberUuid,
                VersionMutationResp.class,
                () -> memberService.remove(ledgerUuid, memberUuid, req)
        ));
    }

    @Operation(summary = "恢复成员")
    @PostMapping("/{memberUuid}/restore")
    public Result<MemberResp> restore(
            @PathVariable String ledgerUuid,
            @PathVariable String memberUuid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MemberRoleUpdateReq req
    ) {
        return Result.ok(idempotencyService.execute(
                idempotencyKey,
                "POST",
                "/api/ledgers/" + ledgerUuid + "/members/" + memberUuid + "/restore",
                MemberResp.class,
                () -> memberService.restore(ledgerUuid, memberUuid, req)
        ));
    }
}

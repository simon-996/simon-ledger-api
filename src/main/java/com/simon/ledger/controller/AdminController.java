package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.AdminLoginReq;
import com.simon.ledger.dto.resp.AdminAuditLogResp;
import com.simon.ledger.dto.resp.AdminDashboardResp;
import com.simon.ledger.dto.resp.AdminLedgerRecordResp;
import com.simon.ledger.dto.resp.AdminLoginResp;
import com.simon.ledger.dto.resp.AdminSystemHealthResp;
import com.simon.ledger.dto.resp.AdminUserRecordResp;
import com.simon.ledger.dto.resp.AdminUserResp;
import com.simon.ledger.dto.resp.PageResp;
import com.simon.ledger.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "后台登录")
    @PostMapping("/auth/login")
    public Result<AdminLoginResp> login(@Valid @RequestBody AdminLoginReq req) {
        return Result.ok(adminService.login(req));
    }

    @Operation(summary = "后台退出")
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        adminService.logout();
        return Result.ok();
    }

    @Operation(summary = "当前后台用户")
    @GetMapping("/auth/me")
    public Result<AdminUserResp> me() {
        return Result.ok(adminService.me());
    }

    @Operation(summary = "后台总览")
    @GetMapping("/dashboard")
    public Result<AdminDashboardResp> dashboard() {
        return Result.ok(adminService.dashboard());
    }

    @Operation(summary = "后台用户列表")
    @GetMapping("/users")
    public Result<PageResp<AdminUserRecordResp>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return Result.ok(adminService.users(keyword, page, pageSize));
    }

    @Operation(summary = "后台账本列表")
    @GetMapping("/ledgers")
    public Result<PageResp<AdminLedgerRecordResp>> ledgers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return Result.ok(adminService.ledgers(keyword, page, pageSize));
    }

    @Operation(summary = "后台审计日志")
    @GetMapping("/audit-logs")
    public Result<PageResp<AdminAuditLogResp>> auditLogs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return Result.ok(adminService.auditLogs(page, pageSize));
    }

    @Operation(summary = "后台系统健康")
    @GetMapping("/system/health")
    public Result<AdminSystemHealthResp> systemHealth() {
        return Result.ok(adminService.systemHealth());
    }
}

package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.AuthLoginReq;
import com.simon.ledger.dto.req.AuthRegisterReq;
import com.simon.ledger.dto.resp.AuthLoginResp;
import com.simon.ledger.dto.resp.AuthUserResp;
import com.simon.ledger.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<AuthUserResp> register(@Valid @RequestBody AuthRegisterReq req) {
        return Result.ok(authService.register(req));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<AuthLoginResp> login(@Valid @RequestBody AuthLoginReq req) {
        return Result.ok(authService.login(req));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "当前用户")
    @GetMapping("/me")
    public Result<AuthUserResp> me() {
        return Result.ok(authService.me());
    }
}

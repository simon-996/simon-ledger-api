package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/health", "/api/health"})
    public Result<Map<String, String>> health() {
        return Result.ok(Map.of("status", "up"));
    }
}

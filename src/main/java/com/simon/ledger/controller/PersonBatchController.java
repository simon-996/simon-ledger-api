package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Person")
@RestController
@RequiredArgsConstructor
public class PersonBatchController {

    private final PersonService personService;

    @Operation(summary = "批量查询账本参与人")
    @GetMapping("/api/ledgers/people")
    public Result<Map<String, List<PersonResp>>> batchList(@RequestParam String ledgerUuids) {
        return Result.ok(personService.batchList(ledgerUuids));
    }
}

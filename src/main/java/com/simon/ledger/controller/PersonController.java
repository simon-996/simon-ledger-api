package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.PersonCreateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.service.PersonService;
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

@Tag(name = "Person")
@RestController
@RequestMapping("/api/ledgers/{ledgerUuid}/people")
@RequiredArgsConstructor
public class PersonController {

    private final PersonService personService;

    @Operation(summary = "参与人列表")
    @GetMapping
    public Result<List<PersonResp>> list(@PathVariable String ledgerUuid) {
        return Result.ok(personService.list(ledgerUuid));
    }

    @Operation(summary = "新增参与人")
    @PostMapping
    public Result<PersonResp> create(@PathVariable String ledgerUuid, @Valid @RequestBody PersonCreateReq req) {
        return Result.ok(personService.create(ledgerUuid, req));
    }

    @Operation(summary = "编辑参与人")
    @PutMapping("/{personUuid}")
    public Result<PersonResp> update(
            @PathVariable String ledgerUuid,
            @PathVariable String personUuid,
            @Valid @RequestBody PersonUpdateReq req
    ) {
        return Result.ok(personService.update(ledgerUuid, personUuid, req));
    }

    @Operation(summary = "删除参与人")
    @DeleteMapping("/{personUuid}")
    public Result<Void> delete(@PathVariable String ledgerUuid, @PathVariable String personUuid) {
        personService.delete(ledgerUuid, personUuid);
        return Result.ok();
    }
}

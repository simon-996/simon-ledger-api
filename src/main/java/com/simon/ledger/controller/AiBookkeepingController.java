package com.simon.ledger.controller;

import com.simon.ledger.common.Result;
import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.dto.resp.AiCapabilityResp;
import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.dto.resp.AiTranscriptionResp;
import com.simon.ledger.service.impl.AiBookkeepingService;
import com.simon.ledger.service.impl.AiTranscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledgers/{ledgerUuid}/ai-bookkeeping")
@RequiredArgsConstructor
public class AiBookkeepingController {
    private final AiBookkeepingService service;
    private final AiTranscriptionService transcription;

    @GetMapping("/capability")
    public Result<AiCapabilityResp> capability(@PathVariable String ledgerUuid) {
        return Result.ok(service.capability(ledgerUuid));
    }

    @PostMapping("/parse")
    public Result<AiDraftResp> parse(@PathVariable String ledgerUuid, @Valid @RequestBody AiParseReq request) {
        return Result.ok(service.parse(ledgerUuid, request));
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Result<AiTranscriptionResp> transcribe(@PathVariable String ledgerUuid, @RequestBody byte[] pcm) {
        return Result.ok(transcription.transcribe(ledgerUuid, pcm));
    }
}

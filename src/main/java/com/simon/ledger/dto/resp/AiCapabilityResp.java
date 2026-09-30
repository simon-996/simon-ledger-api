package com.simon.ledger.dto.resp;

public record AiCapabilityResp(boolean textAvailable, boolean voiceAvailable, String reason) {
}

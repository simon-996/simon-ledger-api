package com.simon.ledger.dto.resp;

public record AiCapabilityResp(boolean textAvailable, boolean voiceAvailable, String reason,
                               int draftSchemaVersion) {
    public AiCapabilityResp(boolean textAvailable, boolean voiceAvailable, String reason) {
        this(textAvailable, voiceAvailable, reason, 2);
    }
}

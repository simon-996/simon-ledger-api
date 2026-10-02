package com.simon.ledger.infrastructure.ai;

final class AiDiagnosticLog {
    private static final int MAX_IDENTIFIER_LENGTH = 96;

    private AiDiagnosticLog() {}

    static String safeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        String safe = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return safe.substring(0, Math.min(safe.length(), MAX_IDENTIFIER_LENGTH));
    }
}

package com.simon.ledger.infrastructure.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiDiagnosticLogTests {
    @Test
    void sanitizesAndBoundsProviderIdentifiersBeforeLogging() {
        assertEquals("request__forged_1", AiDiagnosticLog.safeIdentifier("request\r\nforged=1"));
        assertEquals(96, AiDiagnosticLog.safeIdentifier("x".repeat(120)).length());
        assertEquals("x____", AiDiagnosticLog.safeIdentifier("x \n\t "));
    }
}

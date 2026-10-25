package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.dto.req.LedgerCreateReq;
import com.simon.ledger.dto.resp.LedgerResp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CurrencyContractRegressionTests {
    @Test void migrationNormalizesLegacyCnyCompatibilityAlias() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("sql/008_add_currency_rates_to_cny.sql"))
                .replaceAll("\\s+"," ");
        assertTrue(sql.contains("UPDATE ledger SET exchange_rate_to_cny = 1 WHERE UPPER(TRIM(base_currency_code)) = 'CNY'"));
    }

    @Test void migrationCurrencyFilterRetainsLegacyAlphanumericCodes() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("sql/008_add_currency_rates_to_cny.sql"));
        var matcher = java.util.regex.Pattern.compile("REGEXP '([^']+)'").matcher(sql);
        assertTrue(matcher.find());
        for (String code : new String[]{"USD", "USDT", "A123456789012345"}) {
            assertTrue(java.util.regex.Pattern.matches(matcher.group(1),code),code);
        }
        for (String code : new String[]{"US", "1USD", "A1234567890123456", "USD$"}) {
            assertFalse(java.util.regex.Pattern.matches(matcher.group(1),code),code);
        }
    }

    @Test void ledgerRequestValidationAcceptsLegacyExtendedCode() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        LedgerCreateReq request = new LedgerCreateReq(); request.setName("Legacy");
        request.setBaseCurrencyCode("USDT"); request.setExchangeRateToCny(new java.math.BigDecimal("7"));
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test void ledgerRequestRoundTripsManualRateMap() throws Exception {
        ObjectMapper json = new ObjectMapper();
        LedgerCreateReq request = assertDoesNotThrow(() -> json.readValue("""
                {"name":"Trip","baseCurrencyCode":"EUR","exchangeRateToCny":8,
                 "currencyRatesToCny":{"CNY":1,"USD":7,"EUR":8}}
                """, LedgerCreateReq.class));
        assertEquals(7, json.valueToTree(request).path("currencyRatesToCny").path("USD").asInt());
    }
    @Test void ledgerResponseExposesManualRateMap() {
        assertTrue(new ObjectMapper().valueToTree(new LedgerResp()).has("currencyRatesToCny"));
    }
}

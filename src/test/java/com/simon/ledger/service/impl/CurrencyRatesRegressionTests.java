package com.simon.ledger.service.impl;

import com.simon.ledger.common.CurrencyRates;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.Ledger;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CurrencyRatesRegressionTests {
    @Test void legacyCnyRateIsAlwaysReadAsOneWithoutMutatingOriginalData() {
        Ledger legacy = ledger("CNY","7.2");
        legacy.setCurrencyRatesToCny(Map.of("CNY",new BigDecimal("7.2"),"USD",new BigDecimal("7")));
        var rates = assertDoesNotThrow(() -> CurrencyRates.effective(legacy));
        assertEquals(BigDecimal.ONE,rates.get("CNY"));
        assertEquals(new BigDecimal("7"),rates.get("USD"));
        assertEquals(new BigDecimal("100.00"),CurrencyRates.toCny(legacy,"CNY",new BigDecimal("100.00")));
        assertEquals(new BigDecimal("7.2"),legacy.getExchangeRateToCny());
    }

    @Test void newCnyWritesStillRejectNonUnitLegacyRate() {
        assertThrows(BusinessException.class,() -> CurrencyRates.merge(Map.of(),null,"CNY",new BigDecimal("7.2")));
    }

    @Test void legacyExtendedCurrencyCodesKeepRateAndOriginalAmount() {
        for (String code : new String[]{"USDT", "XABC123", "A123456789012345"}) {
            Ledger legacy = ledger(code,"7");
            var rates = assertDoesNotThrow(() -> CurrencyRates.effective(legacy));
            assertEquals(new BigDecimal("7"),rates.get(code));
            assertEquals(new BigDecimal("700.00"),CurrencyRates.toCny(legacy,code,new BigDecimal("100.00")));
            assertEquals(code,legacy.getBaseCurrencyCode());
        }
        for (String code : new String[]{"US", "1USD", "A1234567890123456", "USD$"}) {
            assertThrows(BusinessException.class,() -> CurrencyRates.code(code));
        }
    }

    @Test void switchingBaseRetainsOldRateAndCurrentLegacyOverridesMap() {
        Ledger ledger = ledger("USD", "7");
        var merged = CurrencyRates.merge(CurrencyRates.effective(ledger), Map.of("EUR", new BigDecimal("9")), "EUR", new BigDecimal("8"));
        assertEquals(0, new BigDecimal("7").compareTo(merged.get("USD")));
        assertEquals(0, new BigDecimal("8").compareTo(merged.get("EUR")));
        ledger.setBaseCurrencyCode("EUR"); ledger.setExchangeRateToCny(new BigDecimal("8")); ledger.setCurrencyRatesToCny(merged);
        assertEquals(new BigDecimal("700.00"), CurrencyRates.toCny(ledger,"USD",new BigDecimal("100")));
        ledger.setCurrencyRatesToCny(CurrencyRates.merge(merged, Map.of("USD",new BigDecimal("7.5")), "EUR", new BigDecimal("8")));
        assertEquals(new BigDecimal("750.00"), CurrencyRates.toCny(ledger,"USD",new BigDecimal("100")));
    }
    @Test void rejectsInvalidPrecisionUnknownRatesAndNonUnitCny() {
        for (String rate : new String[]{"0", "-1", "0.000000001", "10000000000", "1.000000001"}) {
            assertThrows(BusinessException.class, () -> CurrencyRates.merge(Map.of(), Map.of("USD",new BigDecimal(rate)), "CNY",BigDecimal.ONE),rate);
        }
        assertThrows(BusinessException.class, () -> CurrencyRates.merge(Map.of(), Map.of("CNY",new BigDecimal("2")), "USD",new BigDecimal("7")));
        assertThrows(BusinessException.class, () -> CurrencyRates.toCny(ledger("USD","7"),"EUR",BigDecimal.ONE));
        assertThrows(BusinessException.class, () -> CurrencyRates.merge(Map.of(),Map.of("USD$",BigDecimal.ONE),"CNY",BigDecimal.ONE));
    }
    private Ledger ledger(String code, String rate) { Ledger ledger = new Ledger(); ledger.setBaseCurrencyCode(code); ledger.setExchangeRateToCny(new BigDecimal(rate)); return ledger; }
}

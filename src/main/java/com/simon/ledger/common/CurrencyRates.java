package com.simon.ledger.common;

import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.Ledger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CurrencyRates {
    private CurrencyRates() {}
    public static String code(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9]{2,15}")) throw new BusinessException(ErrorCode.BAD_REQUEST, "币种须为 3 至 16 位字母数字代码，首位须为字母");
        return code;
    }
    public static Map<String, BigDecimal> effective(Ledger ledger) {
        String baseCode = code(ledger.getBaseCurrencyCode());
        // Older APIs accepted arbitrary legacy rates for CNY. Normalize only reads;
        // new writes still go through merge's strict CNY=1 validation.
        BigDecimal legacyRate = "CNY".equals(baseCode) ? BigDecimal.ONE : ledger.getExchangeRateToCny();
        return merge(Map.of(), ledger.getCurrencyRatesToCny(), baseCode, legacyRate);
    }
    public static BigDecimal baseRateToCny(Ledger ledger) {
        return effective(ledger).get(code(ledger.getBaseCurrencyCode()));
    }
    public static Map<String, BigDecimal> merge(Map<String, BigDecimal> previous, Map<String, BigDecimal> requested,
                                               String base, BigDecimal legacyRate) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        if (previous != null) rates.putAll(previous);
        if (requested != null) {
            if (requested.size() > 32) throw invalid();
            requested.forEach((key, value) -> { validate(value); rates.put(code(key), value); });
        }
        String baseCode = code(base);
        if (legacyRate != null) { validate(legacyRate); rates.put(baseCode, legacyRate); }
        if (rates.containsKey("CNY") && rates.get("CNY").compareTo(BigDecimal.ONE) != 0) throw invalid();
        rates.put("CNY", BigDecimal.ONE);
        if (rates.size() > 32) throw invalid();
        if (!rates.containsKey(baseCode)) throw missing(baseCode);
        return rates;
    }
    public static BigDecimal toCny(Ledger ledger, String currency, BigDecimal amount) {
        String code = code(currency);
        BigDecimal rate = effective(ledger).get(code);
        if (rate == null) throw missing(code);
        validate(rate);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
    public static void validate(BigDecimal rate) {
        if (rate == null || rate.compareTo(new BigDecimal("0.00000001")) < 0 || rate.scale() > 8
                || rate.precision() - rate.scale() > 10) throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.BAD_REQUEST, "汇率须为正数，最多 10 位整数和 8 位小数；CNY 必须为 1，最多 32 个币种"); }
    private static BusinessException missing(String code) { return new BusinessException(ErrorCode.BAD_REQUEST, "缺少 " + code + " 对人民币的手动汇率，请先补充账本汇率"); }
}

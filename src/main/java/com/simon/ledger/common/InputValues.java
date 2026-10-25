package com.simon.ledger.common;

import com.simon.ledger.common.exception.BusinessException;
import java.math.BigDecimal;

public final class InputValues {
    private InputValues() {}
    public static String avatar(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 255) throw new BusinessException(ErrorCode.BAD_REQUEST, "头像不能超过 255 个字符");
        return normalized;
    }
    public static void amount(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.precision() - value.scale() > 16) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "金额须为正数，最多 16 位整数和 2 位小数");
        }
    }
}

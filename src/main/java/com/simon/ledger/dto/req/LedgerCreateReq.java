package com.simon.ledger.dto.req;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LedgerCreateReq {

    @NotBlank(message = "账本名称不能为空")
    @Size(max = 128, message = "账本名称不能超过 128 个字符")
    private String name;

    @NotBlank(message = "默认币种不能为空")
    @Size(max = 16, message = "默认币种不能超过 16 个字符")
    private String baseCurrencyCode;

    @NotNull(message = "对人民币汇率不能为空")
    @DecimalMin(value = "0.00000001", message = "对人民币汇率必须大于 0")
    private BigDecimal exchangeRateToCny;
}

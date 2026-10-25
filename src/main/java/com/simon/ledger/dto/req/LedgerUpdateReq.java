package com.simon.ledger.dto.req;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

@Data
public class LedgerUpdateReq {

    @NotNull(message = "版本号不能为空")
    @Min(value = 1, message = "版本号必须大于 0")
    private Integer version;

    @NotBlank(message = "账本名称不能为空")
    @Size(max = 128, message = "账本名称不能超过 128 个字符")
    private String name;

    @NotBlank(message = "默认币种不能为空")
    @Size(max = 16, message = "默认币种不能超过 16 个字符")
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{2,15}", message = "币种须为 3 至 16 位字母数字代码，首位须为字母")
    private String baseCurrencyCode;

    @NotNull(message = "对人民币汇率不能为空")
    @DecimalMin(value = "0.00000001", message = "对人民币汇率必须大于 0")
    @Digits(integer = 10, fraction = 8, message = "汇率最多 10 位整数和 8 位小数")
    private BigDecimal exchangeRateToCny;

    @Size(max = 32, message = "最多 32 个币种汇率")
    private Map<String, BigDecimal> currencyRatesToCny;
}

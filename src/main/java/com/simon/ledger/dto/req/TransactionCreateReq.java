package com.simon.ledger.dto.req;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TransactionCreateReq {

    @NotNull(message = "流水类型不能为空")
    private Integer type;

    private String payerPersonUuid;

    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "金额必须大于 0")
    private BigDecimal amount;

    @NotBlank(message = "币种不能为空")
    @Size(max = 16, message = "币种不能超过 16 个字符")
    private String currencyCode;

    @NotBlank(message = "分类不能为空")
    @Size(max = 64, message = "分类不能超过 64 个字符")
    private String category;

    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String note;

    @NotNull(message = "发生时间不能为空")
    private LocalDateTime happenedAt;

    @NotBlank(message = "clientOperationId 不能为空")
    @Size(max = 128, message = "clientOperationId 不能超过 128 个字符")
    private String clientOperationId;

    @NotEmpty(message = "参与人不能为空")
    private List<String> personUuids;
}

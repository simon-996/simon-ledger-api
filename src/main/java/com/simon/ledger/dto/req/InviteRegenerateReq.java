package com.simon.ledger.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InviteRegenerateReq {

    @NotBlank(message = "默认角色不能为空")
    private String role;

    @NotNull(message = "有效天数不能为空")
    private Integer days;

    @NotNull(message = "最大使用次数不能为空")
    @Min(value = 1, message = "最大使用次数必须大于 0")
    private Integer maxUses;
}

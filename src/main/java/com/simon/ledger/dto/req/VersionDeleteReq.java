package com.simon.ledger.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VersionDeleteReq {
    @NotNull(message = "版本号不能为空")
    @Min(value = 1, message = "版本号必须大于 0")
    private Integer version;
}

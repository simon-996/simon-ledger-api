package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PersonUpdateReq {

    @NotNull(message = "版本号不能为空")
    @Min(value = 1, message = "版本号必须大于 0")
    private Integer version;

    @NotBlank(message = "参与人名称不能为空")
    @Size(max = 64, message = "参与人名称不能超过 64 个字符")
    private String name;

    @Size(max = 64, message = "头像不能超过 64 个字符")
    private String avatar;

    private String linkedUserUuid;
}

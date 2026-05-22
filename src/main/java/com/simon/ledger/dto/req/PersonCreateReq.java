package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PersonCreateReq {

    @NotBlank(message = "参与人名称不能为空")
    @Size(max = 64, message = "参与人名称不能超过 64 个字符")
    private String name;

    @Size(max = 64, message = "头像不能超过 64 个字符")
    private String avatar;

    private String linkedUserUuid;
}

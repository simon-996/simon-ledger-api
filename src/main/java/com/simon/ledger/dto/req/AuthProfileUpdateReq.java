package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthProfileUpdateReq {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称不能超过 64 个字符")
    private String nickname;

    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;
}

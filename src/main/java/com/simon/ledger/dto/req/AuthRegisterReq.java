package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthRegisterReq {

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱不能超过 128 个字符")
    private String email;

    @Size(max = 32, message = "手机号不能超过 32 个字符")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度必须在 6 到 64 位之间")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称不能超过 64 个字符")
    private String nickname;

    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;
}

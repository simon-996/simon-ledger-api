package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MemberRoleUpdateReq {

    @NotBlank(message = "角色不能为空")
    private String role;
}

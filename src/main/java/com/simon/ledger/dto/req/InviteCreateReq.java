package com.simon.ledger.dto.req;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InviteCreateReq {

    @NotBlank(message = "默认角色不能为空")
    private String role;

    @Min(value = 1, message = "最大使用次数必须大于 0")
    private Integer maxUses;

    @NotNull(message = "过期时间不能为空")
    @Future(message = "过期时间必须晚于当前时间")
    private LocalDateTime expiresAt;
}

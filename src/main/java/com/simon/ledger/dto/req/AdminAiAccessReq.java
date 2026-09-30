package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminAiAccessReq {
    @NotNull
    private Boolean enabled;
}

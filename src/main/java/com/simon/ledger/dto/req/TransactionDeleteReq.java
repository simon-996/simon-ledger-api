package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransactionDeleteReq {

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

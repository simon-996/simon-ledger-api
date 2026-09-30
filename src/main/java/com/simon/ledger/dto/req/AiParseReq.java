package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiParseReq {
    @NotBlank
    private String text;
    @NotBlank
    private String zone;
}

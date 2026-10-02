package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class AiParseReq {
    @NotBlank
    private String text;
    @NotBlank
    private String zone;
    private List<String> expenseCategories;
    private List<String> incomeCategories;
}

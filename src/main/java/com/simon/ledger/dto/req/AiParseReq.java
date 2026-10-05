package com.simon.ledger.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AiParseReq {
    @NotBlank
    private String text;
    @NotBlank
    private String zone;

    @Min(1)
    @Max(2)
    private Integer schemaVersion = 1;

    @Size(max = 100)
    private List<String> expenseCategories = List.of();

    @Size(max = 100)
    private List<String> incomeCategories = List.of();
}

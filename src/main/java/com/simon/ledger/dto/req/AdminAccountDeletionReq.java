package com.simon.ledger.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AdminAccountDeletionReq {

    @NotBlank private String fingerprint;
    @NotBlank private String confirmUuid;
    @NotNull private List<@Valid Successor> successors;

    @Data
    public static class Successor {
        @NotBlank private String ledgerUuid;
        @NotBlank private String userUuid;
    }
}

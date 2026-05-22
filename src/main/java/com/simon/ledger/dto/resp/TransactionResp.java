package com.simon.ledger.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TransactionResp {

    private String uuid;

    private String ledgerUuid;

    private Integer type;

    private BigDecimal amount;

    private String currencyCode;

    private String category;

    private String note;

    private String createdByUserUuid;

    private String lastModifiedByUserUuid;

    private String clientOperationId;

    private Integer version;

    private LocalDateTime happenedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<String> personUuids;
}

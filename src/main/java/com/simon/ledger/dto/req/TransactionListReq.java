package com.simon.ledger.dto.req;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class TransactionListReq {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startAt;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endAt;

    private Integer type;

    private String category;

    private String personUuid;

    private Long page = 1L;

    private Long pageSize = 20L;
}

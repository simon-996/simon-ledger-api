package com.simon.ledger.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class PageResp<T> {

    private long page;

    private long pageSize;

    private long total;

    private List<T> records;
}

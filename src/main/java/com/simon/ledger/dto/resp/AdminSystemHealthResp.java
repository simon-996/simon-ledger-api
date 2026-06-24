package com.simon.ledger.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class AdminSystemHealthResp {

    private List<Item> items;

    @Data
    public static class Item {

        private String name;

        private String status;

        private String latency;

        private String detail;
    }
}

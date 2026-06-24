package com.simon.ledger.dto.resp;

import lombok.Data;

@Data
public class AdminDashboardResp {

    private Long userCount;

    private Long ledgerCount;

    private Long disabledUserCount;

    private Long recentChangeCount;
}

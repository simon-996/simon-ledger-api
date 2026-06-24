package com.simon.ledger.service;

import com.simon.ledger.dto.req.AdminLoginReq;
import com.simon.ledger.dto.resp.AdminAuditLogResp;
import com.simon.ledger.dto.resp.AdminDashboardResp;
import com.simon.ledger.dto.resp.AdminLedgerRecordResp;
import com.simon.ledger.dto.resp.AdminLoginResp;
import com.simon.ledger.dto.resp.AdminSystemHealthResp;
import com.simon.ledger.dto.resp.AdminUserRecordResp;
import com.simon.ledger.dto.resp.AdminUserResp;
import com.simon.ledger.dto.resp.PageResp;

public interface AdminService {

    AdminLoginResp login(AdminLoginReq req);

    void logout();

    AdminUserResp me();

    AdminDashboardResp dashboard();

    PageResp<AdminUserRecordResp> users(String keyword, Integer page, Integer pageSize);

    PageResp<AdminLedgerRecordResp> ledgers(String keyword, Integer page, Integer pageSize);

    PageResp<AdminAuditLogResp> auditLogs(Integer page, Integer pageSize);

    AdminSystemHealthResp systemHealth();
}

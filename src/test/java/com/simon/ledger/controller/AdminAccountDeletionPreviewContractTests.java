package com.simon.ledger.controller;

import com.simon.ledger.dto.resp.AdminAccountDeletionPreviewResp;
import com.simon.ledger.service.AdminService;
import com.simon.ledger.service.impl.AdminAccountDeletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAccountDeletionPreviewContractTests {

    @Mock private AdminService adminService;
    @Mock private AdminAccountDeletionService deletionService;

    @Test
    void exposesPreviewUnderExistingAdminRouteAndResultEnvelope() throws Exception {
        AdminAccountDeletionPreviewResp preview = new AdminAccountDeletionPreviewResp();
        preview.setUserUuid("user-uuid");
        preview.setNickname("Name");
        preview.setAccount("name@example.com");
        preview.setFingerprint("a".repeat(64));
        AdminAccountDeletionPreviewResp.LedgerImpact owned = new AdminAccountDeletionPreviewResp.LedgerImpact();
        owned.setUuid("owned-uuid");
        owned.setName("Owned");
        owned.setAction("TRANSFER");
        owned.setMemberCount(2);
        owned.setPersonCount(3);
        owned.setTransactionCount(4);
        AdminAccountDeletionPreviewResp.Member successor = new AdminAccountDeletionPreviewResp.Member();
        successor.setUserUuid("successor-uuid");
        successor.setNickname("Successor");
        successor.setRole("editor");
        owned.getSuccessors().add(successor);
        preview.getOwnedLedgers().add(owned);
        AdminAccountDeletionPreviewResp.LedgerImpact joined = new AdminAccountDeletionPreviewResp.LedgerImpact();
        joined.setUuid("joined-uuid");
        joined.setName("Joined");
        joined.setDeleted(true);
        preview.getJoinedLedgers().add(joined);
        when(deletionService.preview("user-uuid")).thenReturn(preview);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminController(adminService, deletionService)).build();

        mvc.perform(get("/api/admin/users/user-uuid/deletion-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userUuid").value("user-uuid"))
                .andExpect(jsonPath("$.data.nickname").value("Name"))
                .andExpect(jsonPath("$.data.account").value("name@example.com"))
                .andExpect(jsonPath("$.data.ownedLedgers[0].uuid").value("owned-uuid"))
                .andExpect(jsonPath("$.data.ownedLedgers[0].memberCount").value(2))
                .andExpect(jsonPath("$.data.ownedLedgers[0].personCount").value(3))
                .andExpect(jsonPath("$.data.ownedLedgers[0].transactionCount").value(4))
                .andExpect(jsonPath("$.data.ownedLedgers[0].successors[0].userUuid").value("successor-uuid"))
                .andExpect(jsonPath("$.data.ownedLedgers[0].successors[0].nickname").value("Successor"))
                .andExpect(jsonPath("$.data.ownedLedgers[0].successors[0].role").value("editor"))
                .andExpect(jsonPath("$.data.joinedLedgers[0].uuid").value("joined-uuid"))
                .andExpect(jsonPath("$.data.joinedLedgers[0].deleted").value(true))
                .andExpect(jsonPath("$.data.fingerprint").value("a".repeat(64)));

        verify(deletionService).preview("user-uuid");
    }
}

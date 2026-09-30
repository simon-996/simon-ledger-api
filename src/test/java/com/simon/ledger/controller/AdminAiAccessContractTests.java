package com.simon.ledger.controller;

import com.simon.ledger.service.AdminService;
import com.simon.ledger.service.impl.AdminAccountDeletionExecutor;
import com.simon.ledger.service.impl.AdminAccountDeletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAiAccessContractTests {
    @Mock private AdminService adminService;
    @Mock private AdminAccountDeletionService deletionService;
    @Mock private AdminAccountDeletionExecutor deletionExecutor;

    @Test
    void updatesGrantThroughTypedAdminRoute() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AdminController(adminService, deletionService, deletionExecutor)).build();

        mvc.perform(put("/api/admin/users/user-uuid/ai-bookkeeping-access")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(put("/api/admin/users/user-uuid/ai-bookkeeping-access")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}

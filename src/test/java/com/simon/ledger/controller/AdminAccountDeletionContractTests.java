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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAccountDeletionContractTests {

    @Mock private AdminService adminService;
    @Mock private AdminAccountDeletionService previewService;
    @Mock private AdminAccountDeletionExecutor deletionExecutor;

    @Test
    void requiresTypedUuidAndSuccessorSelections() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AdminController(adminService, previewService, deletionExecutor)).build();

        mvc.perform(delete("/api/admin/users/target")
                .contentType("application/json")
                .content("{\"fingerprint\":\"hash\",\"confirmUuid\":\"target\",\"successors\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(deletionExecutor).delete(org.mockito.ArgumentMatchers.eq("target"), any());

        mvc.perform(delete("/api/admin/users/target")
                .contentType("application/json")
                .content("{\"fingerprint\":\"hash\",\"successors\":[]}"))
                .andExpect(status().isBadRequest());
    }
}

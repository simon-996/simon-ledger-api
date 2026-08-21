package com.simon.ledger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LedgerControllerContractTests {

    @Mock private LedgerService ledgerService;
    @Mock private IdempotencyService idempotencyService;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LedgerController(ledgerService, idempotencyService)).build();
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(4);
            return supplier.get();
        });
    }

    @Test
    void putRequiresVersionInValidatedBodyAndUsesExactIdempotencyRoute() throws Exception {
        LedgerResp response = new LedgerResp();
        response.setUuid("ledger-uuid");
        response.setVersion(3);
        when(ledgerService.update(eq("ledger-uuid"), any())).thenReturn(response);

        mvc.perform(put("/api/ledgers/ledger-uuid")
                        .header("Idempotency-Key", "put-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3));

        verify(idempotencyService).execute(eq("put-key"), eq("PUT"), eq("/api/ledgers/ledger-uuid"),
                eq(LedgerResp.class), any());

        mvc.perform(put("/api/ledgers/ledger-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ledger\",\"baseCurrencyCode\":\"CNY\",\"exchangeRateToCny\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRequiresVersionBodyAndReturnsVersionMutationThroughExecute() throws Exception {
        VersionMutationResp response = new VersionMutationResp("ledger-uuid", 3, true);
        when(ledgerService.delete(eq("ledger-uuid"), any())).thenReturn(response);

        mvc.perform(delete("/api/ledgers/ledger-uuid")
                        .header("Idempotency-Key", "delete-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("ledger-uuid"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.deleted").value(true));

        verify(idempotencyService).execute(eq("delete-key"), eq("DELETE"), eq("/api/ledgers/ledger-uuid"),
                eq(VersionMutationResp.class), any());
        verify(idempotencyService, never()).executeVoid(any(), any(), any(), any());

        mvc.perform(delete("/api/ledgers/ledger-uuid").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restoreUsesPostRouteValidatedBodyAndLedgerResponseIdempotency() throws Exception {
        LedgerResp response = new LedgerResp();
        response.setUuid("ledger-uuid");
        response.setVersion(3);
        when(ledgerService.restore(eq("ledger-uuid"), any())).thenReturn(response);

        mvc.perform(post("/api/ledgers/ledger-uuid/restore")
                        .header("Idempotency-Key", "restore-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3));

        verify(idempotencyService).execute(eq("restore-key"), eq("POST"),
                eq("/api/ledgers/ledger-uuid/restore"), eq(LedgerResp.class), any());
    }

    @Test
    void leaveRequiresVersionBodyAndReturnsMemberMutationThroughExecute() throws Exception {
        VersionMutationResp response = new VersionMutationResp("member-uuid", 5, true);
        when(ledgerService.leave(eq("ledger-uuid"), any())).thenReturn(response);

        mvc.perform(post("/api/ledgers/ledger-uuid/leave")
                        .header("Idempotency-Key", "leave-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("member-uuid"))
                .andExpect(jsonPath("$.data.version").value(5));

        verify(idempotencyService).execute(eq("leave-key"), eq("POST"), eq("/api/ledgers/ledger-uuid/leave"),
                eq(VersionMutationResp.class), any());
        verify(idempotencyService, never()).executeVoid(any(), any(), any(), any());

        mvc.perform(post("/api/ledgers/ledger-uuid/leave").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private LedgerUpdateReq updateReq(int version) {
        LedgerUpdateReq req = new LedgerUpdateReq();
        req.setVersion(version);
        req.setName("Ledger");
        req.setBaseCurrencyCode("CNY");
        req.setExchangeRateToCny(BigDecimal.ONE);
        return req;
    }
}

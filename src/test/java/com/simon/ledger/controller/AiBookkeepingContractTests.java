package com.simon.ledger.controller;

import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.dto.resp.AiTranscriptionResp;
import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.service.impl.AiBookkeepingService;
import com.simon.ledger.service.impl.AiTranscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiBookkeepingContractTests {
    @Mock private AiBookkeepingService service;
    @Mock private AiTranscriptionService transcription;

    @Test
    void acceptsClientCategoriesAndSerializesExactRoleMetadataWithLegacyFields() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AiBookkeepingController(service, transcription)).build();
        var response = new com.simon.ledger.service.impl.AiDraftValidator(new com.fasterxml.jackson.databind.ObjectMapper())
                .validate("""
                        {"entries":[{"type":0,"amount":"12","currencyCode":"CNY","payerName":"未知付款人"}]}
                        """, ledger(), 7L, java.time.ZoneId.of("Asia/Shanghai"), List.of());
        when(service.parse(eq("ledger-1"), any())).thenReturn(response);
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"描述十二元","zone":"Asia/Shanghai","expenseCategories":["自定支出"],"incomeCategories":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].paymentMode").value("unconfirmed"))
                .andExpect(jsonPath("$.data.entries[0].personUuids").isEmpty())
                .andExpect(jsonPath("$.data.entries[0].payerPersonUuid").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].unresolvedNames[0]").value("未知付款人"))
                .andExpect(jsonPath("$.data.entries[0].personMatches[0].sourceName").value("未知付款人"))
                .andExpect(jsonPath("$.data.entries[0].personMatches[0].role").value("payer"))
                .andExpect(jsonPath("$.data.entries[0].personMatches[0].personUuid").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].personMatches[0].matchedName").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].personMatches[0].approximate").value(false))
                .andExpect(jsonPath("$.data.entries[0].personMatches[0].candidatePersonUuids").isEmpty());
        var captured = ArgumentCaptor.forClass(AiParseReq.class);
        verify(service).parse(eq("ledger-1"), captured.capture());
        assertEquals(List.of("自定支出"), captured.getValue().getExpenseCategories());
        assertEquals(List.of(), captured.getValue().getIncomeCategories());
    }

    private com.simon.ledger.entity.Ledger ledger() {
        var ledger = new com.simon.ledger.entity.Ledger();
        ledger.setId(5L);
        return ledger;
    }

    @Test
    void parsesTextIntoDraftEnvelopeAndAcceptsOnlyBinaryAudio() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiBookkeepingController(service, transcription)).build();
        AiDraftResp drafts = new AiDraftResp();
        AiDraftResp.Entry entry = new AiDraftResp.Entry();
        entry.setSourceText("早餐18元");
        entry.setType(0);
        entry.setAmount(new java.math.BigDecimal("18.00"));
        entry.setCurrencyCode("CNY");
        drafts.getEntries().add(entry);
        when(service.parse(eq("ledger-1"), any())).thenReturn(drafts);
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"早餐18元\",\"zone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].amount").value(18));

        when(transcription.transcribe(eq("ledger-1"), any(byte[].class)))
                .thenReturn(new AiTranscriptionResp("早餐花了十八元"));
        byte[] pcm = new byte[32000];
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/transcribe")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(pcm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("早餐花了十八元"));
        verify(transcription).transcribe(eq("ledger-1"), any(byte[].class));
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/transcribe")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }
}

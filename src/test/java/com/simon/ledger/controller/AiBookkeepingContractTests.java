package com.simon.ledger.controller;

import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.dto.resp.AiTranscriptionResp;
import com.simon.ledger.service.impl.AiBookkeepingService;
import com.simon.ledger.service.impl.AiTranscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiBookkeepingContractTests {
    @Mock private AiBookkeepingService service;
    @Mock private AiTranscriptionService transcription;

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

    @Test
    void rejectsUnsupportedSemanticDraftVersion() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiBookkeepingController(service, transcription)).build();
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"早餐18元\",\"zone\":\"Asia/Shanghai\",\"schemaVersion\":3}"))
                .andExpect(status().isBadRequest());
    }
}

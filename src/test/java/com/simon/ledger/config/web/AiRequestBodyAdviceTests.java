package com.simon.ledger.config.web;

import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiRequestBodyAdviceTests {
    @Test void rejectsOversizedAiJsonBeforeParserService() throws Exception {
        var access = mock(AiBookkeepingAccess.class);
        var parser = mock(AiBookkeepingService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new com.simon.ledger.controller.AiBookkeepingController(parser, mock(AiTranscriptionService.class)))
                .setControllerAdvice(new RestExceptionHandler(), new AiRequestBodyAdvice(access)).build();
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/parse").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"早餐18元\",\"zone\":\"Asia/Shanghai\",\"padding\":\"" + "x".repeat(33000) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(parser, never()).parse(any(), any());
    }
    @Test void rejectsUnauthorizedAiJsonBeforeParserService() throws Exception {
        var access = mock(AiBookkeepingAccess.class);
        when(access.requireAllowed("ledger-1")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        var parser = mock(AiBookkeepingService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new com.simon.ledger.controller.AiBookkeepingController(parser, mock(AiTranscriptionService.class)))
                .setControllerAdvice(new RestExceptionHandler(), new AiRequestBodyAdvice(access)).build();
        mvc.perform(post("/api/ledgers/ledger-1/ai-bookkeeping/parse").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"早餐18元\",\"zone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isForbidden());
        verify(parser, never()).parse(any(), any());
    }
}

package com.simon.ledger.service.impl;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.AiTranscriptionResp;
import com.simon.ledger.infrastructure.ai.AiProviderConfig;
import com.simon.ledger.infrastructure.ai.TencentSpeechClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiTranscriptionService {
    private static final int MAX_PCM_BYTES = 60 * 16000 * 2;
    private static final int MAX_BASE64_BYTES = 3_000_000;
    private final AiBookkeepingAccess access;
    private final AiUsageLimiter limiter;
    private final TencentSpeechClient speech;
    private final AiProviderConfig config;

    public AiTranscriptionResp transcribe(String ledgerUuid, byte[] pcm) {
        AiBookkeepingAccess.Context context = access.requireAllowed(ledgerUuid);
        if (!config.textAvailable() || !config.voiceAvailable()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "语音记账尚未配置");
        }
        if (pcm == null || pcm.length < 2 || pcm.length % 2 != 0
                || pcm.length > MAX_PCM_BYTES || ((long) pcm.length + 2) / 3 * 4 > MAX_BASE64_BYTES
                || looksLikeFileHeader(pcm)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "录音格式或时长无效");
        }
        limiter.consume("transcribe", context.user().getId());
        return new AiTranscriptionResp(speech.transcribe(pcm));
    }

    private boolean looksLikeFileHeader(byte[] pcm) {
        return pcm.length >= 4 && (
                (pcm[0] == 'R' && pcm[1] == 'I' && pcm[2] == 'F' && pcm[3] == 'F')
                        || (pcm[0] == 'I' && pcm[1] == 'D' && pcm[2] == '3'));
    }
}

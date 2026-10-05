package com.simon.ledger.infrastructure.ai;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.tencentcloudapi.asr.v20190614.AsrClient;
import com.tencentcloudapi.asr.v20190614.models.SentenceRecognitionRequest;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
@RequiredArgsConstructor
public class TencentSpeechClient {
    private static final Logger log = LoggerFactory.getLogger(TencentSpeechClient.class);
    private final AiProviderConfig config;

    public String transcribe(byte[] pcm) {
        if (!config.voiceAvailable()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "语音记账尚未配置");
        }
        try {
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setConnTimeout(5);
            httpProfile.setReadTimeout(25);
            ClientProfile profile = new ClientProfile();
            profile.setHttpProfile(httpProfile);
            AsrClient client = new AsrClient(new Credential(
                    config.tencentSecretId(), config.tencentSecretKey()), "ap-shanghai", profile);
            SentenceRecognitionRequest request = new SentenceRecognitionRequest();
            request.setSourceType(1L);
            request.setVoiceFormat("pcm");
            request.setEngSerViceType("16k_zh");
            request.setData(Base64.getEncoder().encodeToString(pcm));
            request.setDataLen((long) pcm.length);
            String result = client.SentenceRecognition(request).getResult();
            if (result == null || result.isBlank()) {
                throw unavailable();
            }
            return result.trim();
        } catch (TencentCloudSDKException exception) {
            log.warn("Tencent ASR request failed errorCode={} requestId={} exceptionType={}",
                    AiDiagnosticLog.safeIdentifier(exception.getErrorCode()),
                    AiDiagnosticLog.safeIdentifier(exception.getRequestId()),
                    exception.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, "语音识别暂不可用，请稍后重试");
    }
}

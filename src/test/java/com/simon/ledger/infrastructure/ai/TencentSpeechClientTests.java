package com.simon.ledger.infrastructure.ai;

import com.simon.ledger.common.exception.BusinessException;
import com.tencentcloudapi.asr.v20190614.AsrClient;
import com.tencentcloudapi.asr.v20190614.models.SentenceRecognitionRequest;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class TencentSpeechClientTests {
    @Test
    void providerFailureLogsOnlySafeTencentDiagnosticFields() {
        TencentCloudSDKException sdkException = new TencentCloudSDKException(
                "private provider detail", "tencent-request-123", "AuthFailure.InvalidSecretId");
        List<String> messages = new ArrayList<>();
        var appender = new AbstractAppender("tencent-privacy-test", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
        };
        Logger logger = (Logger) LogManager.getLogger(TencentSpeechClient.class);
        var previousLevel = logger.getLevel();
        var previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
        try (MockedConstruction<AsrClient> ignored = mockConstruction(AsrClient.class,
                (client, context) -> when(client.SentenceRecognition(any(SentenceRecognitionRequest.class)))
                        .thenThrow(sdkException))) {
            AiProviderConfig config = new AiProviderConfig("deepseek-test-key", "deepseek-flash",
                    "tencent-test-id", "tencent-test-key");
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> new TencentSpeechClient(config).transcribe(new byte[]{1, 2, 3}));
            assertTrue(exception.getMessage().contains("语音识别暂不可用"));
            String logs = String.join("\n", messages);
            assertTrue(logs.contains("errorCode=AuthFailure.InvalidSecretId"));
            assertTrue(logs.contains("requestId=tencent-request-123"));
            assertFalse(logs.contains("private provider detail"));
            assertFalse(logs.contains("tencent-test-id"));
            assertFalse(logs.contains("tencent-test-key"));
        } finally {
            logger.removeAppender(appender);
            logger.setAdditive(previousAdditive);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}

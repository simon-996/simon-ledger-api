package com.simon.ledger.infrastructure.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AiProviderConfig {
    private static final Logger log = LoggerFactory.getLogger(AiProviderConfig.class);
    private final String deepSeekKey;
    private final String deepSeekModel;
    private final String tencentSecretId;
    private final String tencentSecretKey;

    public AiProviderConfig(@Value("${DEEPSEEK_API_KEY:}") String deepSeekKey,
                            @Value("${DEEPSEEK_MODEL:deepseek-flash}") String deepSeekModel,
                            @Value("${TENCENT_ASR_SECRET_ID:}") String tencentSecretId,
                            @Value("${TENCENT_ASR_SECRET_KEY:}") String tencentSecretKey) {
        this.deepSeekKey = deepSeekKey;
        this.deepSeekModel = deepSeekModel;
        this.tencentSecretId = tencentSecretId;
        this.tencentSecretKey = tencentSecretKey;
        log.info("AI provider configuration deepSeekConfigured={} tencentAsrConfigured={}",
                textAvailable(), voiceAvailable());
    }

    public boolean textAvailable() {
        return deepSeekKey != null && !deepSeekKey.isBlank();
    }

    public boolean voiceAvailable() {
        return tencentSecretId != null && !tencentSecretId.isBlank()
                && tencentSecretKey != null && !tencentSecretKey.isBlank();
    }

    public String deepSeekKey() { return deepSeekKey; }
    public String deepSeekModel() { return deepSeekModel; }
    public String tencentSecretId() { return tencentSecretId; }
    public String tencentSecretKey() { return tencentSecretKey; }
}

package com.simon.ledger.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.dto.resp.AiCapabilityResp;
import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.infrastructure.ai.AiProviderConfig;
import com.simon.ledger.infrastructure.ai.DeepSeekDraftClient;
import com.simon.ledger.mapper.LedgerPersonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AiBookkeepingService {
    private final AiBookkeepingAccess access;
    private final AiUsageLimiter limiter;
    private final DeepSeekDraftClient provider;
    private final LedgerPersonMapper people;
    private final AiDraftValidator validator;
    private final AiProviderConfig config;

    public AiCapabilityResp capability(String ledgerUuid) {
        try {
            access.requireAllowed(ledgerUuid);
        } catch (BusinessException exception) {
            return new AiCapabilityResp(false, false, "未授权或没有账本记账权限");
        }
        boolean text = config.textAvailable();
        boolean voice = text && config.voiceAvailable();
        return new AiCapabilityResp(text, voice, text ? null : "AI 服务尚未配置");
    }

    public AiDraftResp parse(String ledgerUuid, AiParseReq request) {
        AiBookkeepingAccess.Context context = access.requireAllowed(ledgerUuid);
        if (!config.textAvailable()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 文字记账未配置");
        }
        if (request.getText() == null || request.getText().isBlank()
                || request.getText().codePointCount(0, request.getText().length()) > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "描述须在 1 到 1000 字之间");
        }
        final ZoneId zone;
        try {
            zone = ZoneId.of(request.getZone());
        } catch (DateTimeException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时区无效");
        }
        limiter.consume("parse", context.user().getId());
        String result = provider.parse(request.getText(), zone, context.ledger().getBaseCurrencyCode());
        return validator.validate(result, context.ledger(), context.user().getId(), zone,
                people.selectList(Wrappers.<LedgerPerson>lambdaQuery()
                        .eq(LedgerPerson::getLedgerId, context.ledger().getId())
                        .isNull(LedgerPerson::getDeletedAt)));
    }
}

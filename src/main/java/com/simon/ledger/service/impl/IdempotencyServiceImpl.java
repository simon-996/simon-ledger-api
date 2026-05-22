package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.IdempotencyRecord;
import com.simon.ledger.mapper.IdempotencyRecordMapper;
import com.simon.ledger.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final int SUCCESS_CODE = 0;

    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final ObjectMapper objectMapper;

    @Override
    public <T> T execute(String key, String method, String path, Class<T> responseType, Supplier<T> supplier) {
        if (!StringUtils.hasText(key)) {
            return supplier.get();
        }
        Long userId = StpUtil.getLoginIdAsLong();
        IdempotencyRecord exists = existing(userId, key);
        if (exists != null) {
            ensureSameRequest(exists, method, path);
            return readResponse(exists, responseType);
        }

        T response = supplier.get();
        saveRecord(userId, key, method, path, response);
        return response;
    }

    @Override
    public void executeVoid(String key, String method, String path, Runnable runnable) {
        if (!StringUtils.hasText(key)) {
            runnable.run();
            return;
        }
        Long userId = StpUtil.getLoginIdAsLong();
        IdempotencyRecord exists = existing(userId, key);
        if (exists != null) {
            ensureSameRequest(exists, method, path);
            return;
        }
        runnable.run();
        saveRecord(userId, key, method, path, null);
    }

    private IdempotencyRecord existing(Long userId, String key) {
        return idempotencyRecordMapper.selectOne(Wrappers.<IdempotencyRecord>lambdaQuery()
                .eq(IdempotencyRecord::getUserId, userId)
                .eq(IdempotencyRecord::getRequestKey, key.trim()));
    }

    private void ensureSameRequest(IdempotencyRecord record, String method, String path) {
        if (!record.getRequestMethod().equalsIgnoreCase(method) || !record.getRequestPath().equals(path)) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已用于其他请求");
        }
    }

    private <T> T readResponse(IdempotencyRecord record, Class<T> responseType) {
        if (!StringUtils.hasText(record.getResponseBody())) {
            return null;
        }
        try {
            return objectMapper.readValue(record.getResponseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "幂等响应解析失败");
        }
    }

    private void saveRecord(Long userId, String key, String method, String path, Object response) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setUserId(userId);
        record.setRequestKey(key.trim());
        record.setRequestMethod(method);
        record.setRequestPath(path);
        record.setResponseCode(SUCCESS_CODE);
        record.setResponseBody(writeResponse(response));
        record.setCreatedAt(LocalDateTime.now());
        record.setExpiresAt(LocalDateTime.now().plusDays(30));
        idempotencyRecordMapper.insert(record);
    }

    private String writeResponse(Object response) {
        if (response == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "幂等响应序列化失败");
        }
    }
}

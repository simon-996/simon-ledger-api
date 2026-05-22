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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final int PROCESSING_CODE = -1;
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

        String requestKey = key.trim();
        IdempotencyRecord record = processingRecord(userId, requestKey, method, path);
        if (!tryInsert(record)) {
            return readExisting(userId, requestKey, method, path, responseType);
        }

        try {
            T response = supplier.get();
            completeRecord(record, response);
            return response;
        } catch (RuntimeException e) {
            deleteRecord(record);
            throw e;
        }
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

        String requestKey = key.trim();
        IdempotencyRecord record = processingRecord(userId, requestKey, method, path);
        if (!tryInsert(record)) {
            readExisting(userId, requestKey, method, path, Void.class);
            return;
        }

        try {
            runnable.run();
            completeRecord(record, null);
        } catch (RuntimeException e) {
            deleteRecord(record);
            throw e;
        }
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
        if (PROCESSING_CODE == record.getResponseCode()) {
            throw new BusinessException(ErrorCode.CONFLICT, "请求正在处理中，请稍后重试");
        }
        if (!StringUtils.hasText(record.getResponseBody())) {
            return null;
        }
        try {
            return objectMapper.readValue(record.getResponseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "幂等响应解析失败");
        }
    }

    private <T> T readExisting(Long userId, String key, String method, String path, Class<T> responseType) {
        IdempotencyRecord exists = existing(userId, key);
        if (exists == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "请求正在处理中，请稍后重试");
        }
        ensureSameRequest(exists, method, path);
        return readResponse(exists, responseType);
    }

    private IdempotencyRecord processingRecord(Long userId, String key, String method, String path) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setUserId(userId);
        record.setRequestKey(key);
        record.setRequestMethod(method);
        record.setRequestPath(path);
        record.setResponseCode(PROCESSING_CODE);
        record.setResponseBody(null);
        record.setCreatedAt(LocalDateTime.now());
        record.setExpiresAt(LocalDateTime.now().plusDays(30));
        return record;
    }

    private boolean tryInsert(IdempotencyRecord record) {
        try {
            idempotencyRecordMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void completeRecord(IdempotencyRecord record, Object response) {
        record.setResponseCode(SUCCESS_CODE);
        record.setResponseBody(writeResponse(response));
        idempotencyRecordMapper.updateById(record);
    }

    private void deleteRecord(IdempotencyRecord record) {
        if (record.getId() != null) {
            idempotencyRecordMapper.deleteById(record.getId());
        }
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

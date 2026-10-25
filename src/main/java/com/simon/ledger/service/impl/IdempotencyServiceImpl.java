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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final int PROCESSING_CODE = -1;
    private static final int INVALIDATED_CODE = -2;
    private static final int SUCCESS_CODE = 0;
    private static final int MAX_REQUEST_KEY_LENGTH = 128;

    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public <T> T execute(String key, String method, String path, Class<T> responseType, Supplier<T> supplier) {
        if (!StringUtils.hasText(key)) return supplier.get();
        String requestKey = normalizeRequestKey(key);
        Long userId = StpUtil.getLoginIdAsLong();
        IdempotencyRecord exists = existing(userId, requestKey);
        if (exists != null) {
            ensureSameRequest(exists, method, path);
            return readResponse(exists, responseType);
        }

        // Business locks (ledger -> account -> people) precede cache/FK locks. Nothing is
        // committed until the reservation, business writes and serialized response all succeed.
        // Concurrent callers may do speculative work; the losing unique-key insert aborts
        // its entire transaction, so a retry can safely replay the winner's response.
        T response = supplier.get();
        IdempotencyRecord record = processingRecord(userId, requestKey, method, path);
        if (!tryInsert(record)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一幂等键正在提交，请重试原请求");
        }
        completeRecord(record, response);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void executeVoid(String key, String method, String path, Runnable runnable) {
        execute(key, method, path, Void.class, () -> { runnable.run(); return null; });
    }

    private IdempotencyRecord existing(Long userId, String key) {
        return idempotencyRecordMapper.selectOne(Wrappers.<IdempotencyRecord>lambdaQuery()
                .eq(IdempotencyRecord::getUserId, userId)
                .eq(IdempotencyRecord::getRequestKey, key));
    }

    private String normalizeRequestKey(String key) {
        String requestKey = key.trim();
        if (requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 个字符");
        }
        return requestKey;
    }

    private void ensureSameRequest(IdempotencyRecord record, String method, String path) {
        if (!record.getRequestMethod().equalsIgnoreCase(method) || !record.getRequestPath().equals(path)) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已用于其他请求");
        }
    }

    private <T> T readResponse(IdempotencyRecord record, Class<T> responseType) {
        if (INVALIDATED_CODE == record.getResponseCode()) {
            throw new BusinessException(ErrorCode.CONFLICT, "缓存响应已失效，请使用新幂等键重新请求");
        }
        if (PROCESSING_CODE == record.getResponseCode()) {
            throw new BusinessException(ErrorCode.CONFLICT, record.getExpiresAt() != null && record.getExpiresAt().isBefore(LocalDateTime.now())
                    ? "旧请求结果无法确认，幂等键已保留，请刷新数据后处理" : "请求正在处理中，请稍后重试");
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
        int affected = idempotencyRecordMapper.update(null, Wrappers.<IdempotencyRecord>lambdaUpdate()
                .eq(IdempotencyRecord::getId, record.getId())
                .eq(IdempotencyRecord::getResponseCode, PROCESSING_CODE)
                .set(IdempotencyRecord::getResponseCode, SUCCESS_CODE)
                .set(IdempotencyRecord::getResponseBody, writeResponse(response)));
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等响应已失效，请重试");
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

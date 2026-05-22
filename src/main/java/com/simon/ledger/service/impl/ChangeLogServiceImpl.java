package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.ChangeLogResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerChangeLog;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerChangeLogMapper;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChangeLogServiceImpl implements ChangeLogService {

    private static final int MEMBER_STATUS_ACTIVE = 1;
    private static final int MAX_RECORD_RETRY = 3;

    private final LedgerChangeLogMapper ledgerChangeLogMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final UserAccountMapper userAccountMapper;

    @Override
    public void record(Long ledgerId, String entityType, String entityUuid, String operation, Long operatorUserId) {
        for (int i = 0; i < MAX_RECORD_RETRY; i++) {
            try {
                doRecord(ledgerId, entityType, entityUuid, operation, operatorUserId);
                return;
            } catch (DuplicateKeyException e) {
                if (i == MAX_RECORD_RETRY - 1) {
                    throw e;
                }
            }
        }
    }

    private void doRecord(Long ledgerId, String entityType, String entityUuid, String operation, Long operatorUserId) {
        Integer maxVersion = ledgerChangeLogMapper.selectList(Wrappers.<LedgerChangeLog>lambdaQuery()
                        .eq(LedgerChangeLog::getLedgerId, ledgerId))
                .stream()
                .map(LedgerChangeLog::getVersion)
                .max(Comparator.naturalOrder())
                .orElse(0);

        LedgerChangeLog log = new LedgerChangeLog();
        log.setUuid(IdUtil.fastSimpleUUID());
        log.setLedgerId(ledgerId);
        log.setEntityType(entityType);
        log.setEntityUuid(entityUuid);
        log.setOperation(operation);
        log.setOperatorUserId(operatorUserId);
        log.setVersion(maxVersion + 1);
        log.setCreatedAt(LocalDateTime.now());
        ledgerChangeLogMapper.insert(log);
    }

    @Override
    public List<ChangeLogResp> changes(String ledgerUuid, Integer afterVersion) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = ledgerMapper.selectOne(Wrappers.<Ledger>lambdaQuery()
                .eq(Ledger::getUuid, ledgerUuid)
                .isNull(Ledger::getDeletedAt));
        if (ledger == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }
        LedgerMember member = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt));
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        List<LedgerChangeLog> logs = ledgerChangeLogMapper.selectList(Wrappers.<LedgerChangeLog>lambdaQuery()
                .eq(LedgerChangeLog::getLedgerId, ledger.getId())
                .gt(LedgerChangeLog::getVersion, afterVersion == null ? 0 : afterVersion)
                .orderByAsc(LedgerChangeLog::getVersion));
        Map<Long, UserAccount> userMap = userMap(logs);
        return logs.stream().map(log -> toResp(ledger, log, userMap.get(log.getOperatorUserId()))).toList();
    }

    private Map<Long, UserAccount> userMap(List<LedgerChangeLog> logs) {
        List<Long> userIds = logs.stream().map(LedgerChangeLog::getOperatorUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private ChangeLogResp toResp(Ledger ledger, LedgerChangeLog log, UserAccount operator) {
        ChangeLogResp resp = new ChangeLogResp();
        resp.setUuid(log.getUuid());
        resp.setLedgerUuid(ledger.getUuid());
        resp.setEntityType(log.getEntityType());
        resp.setEntityUuid(log.getEntityUuid());
        resp.setOperation(log.getOperation());
        resp.setOperatorUserUuid(operator == null ? null : operator.getUuid());
        resp.setVersion(log.getVersion());
        resp.setCreatedAt(log.getCreatedAt());
        return resp;
    }
}

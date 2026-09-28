package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.ledger.dto.req.TransactionListReq;
import com.simon.ledger.dto.resp.ChangeLogResp;
import com.simon.ledger.dto.resp.PageResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerChangeLog;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.mapper.LedgerChangeLogMapper;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.LedgerTransactionMapper;
import com.simon.ledger.mapper.LedgerTransactionPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletedAccountHistoryTests {

    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper ledgerMemberMapper;
    @Mock private LedgerPersonMapper ledgerPersonMapper;
    @Mock private LedgerTransactionMapper transactionMapper;
    @Mock private LedgerTransactionPersonMapper transactionPersonMapper;
    @Mock private UserAccountMapper userAccountMapper;
    @Mock private LedgerChangeLogMapper changeLogMapper;
    @Mock private ChangeLogService changeLogService;
    @Mock private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(Ledger.class, LedgerMember.class, LedgerTransaction.class, LedgerChangeLog.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
            }
        }
    }

    @Test
    void retainedTransactionNamesDeletedCreatorWithoutChangingAmount() {
        Ledger ledger = ledger();
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setId(9L);
        transaction.setUuid("transaction-uuid");
        transaction.setLedgerId(ledger.getId());
        transaction.setAmount(new BigDecimal("38.50"));
        transaction.setCreatedByUserId(null);
        Page<LedgerTransaction> page = new Page<>(1, 20);
        page.setRecords(List.of(transaction));
        page.setTotal(1);
        when(ledgerMapper.selectOne(any())).thenReturn(ledger);
        when(ledgerMemberMapper.selectOne(any())).thenReturn(activeMember());
        when(transactionMapper.selectPage(any(), any())).thenReturn(page);
        when(transactionPersonMapper.selectList(any())).thenReturn(List.of());
        TransactionServiceImpl service = new TransactionServiceImpl(
                ledgerMapper, ledgerMemberMapper, ledgerPersonMapper, transactionPersonMapper,
                userAccountMapper, changeLogService, transactionManager);
        ReflectionTestUtils.setField(service, "baseMapper", transactionMapper);

        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            PageResp<TransactionResp> result = service.list("ledger-uuid", new TransactionListReq());
            assertEquals("已注销用户", result.getRecords().getFirst().getCreatedByNickname());
            assertNull(result.getRecords().getFirst().getCreatedByUserUuid());
            assertEquals(new BigDecimal("38.50"), result.getRecords().getFirst().getAmount());
        }
    }

    @Test
    void changeHistoryWithAnonymousOperatorSkipsUserLookup() {
        LedgerChangeLog entry = new LedgerChangeLog();
        entry.setUuid("change-uuid");
        entry.setLedgerId(3L);
        entry.setOperatorUserId(null);
        entry.setVersion(4);
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(ledgerMemberMapper.selectOne(any())).thenReturn(activeMember());
        when(changeLogMapper.selectList(any())).thenReturn(List.of(entry));
        ChangeLogServiceImpl service = new ChangeLogServiceImpl(
                changeLogMapper, ledgerMapper, ledgerMemberMapper, userAccountMapper);

        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            List<ChangeLogResp> result = service.changes("ledger-uuid", 3);
            assertEquals(4, result.getFirst().getVersion());
            assertNull(result.getFirst().getOperatorUserUuid());
        }
        verify(userAccountMapper, never()).selectList(any());
    }

    private Ledger ledger() {
        Ledger ledger = new Ledger();
        ledger.setId(3L);
        ledger.setUuid("ledger-uuid");
        return ledger;
    }

    private LedgerMember activeMember() {
        LedgerMember member = new LedgerMember();
        member.setLedgerId(3L);
        member.setUserId(7L);
        member.setStatus(1);
        return member;
    }
}

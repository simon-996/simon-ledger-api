package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.*;
import com.simon.ledger.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;

class StatsReviewRegressionTests {
    LedgerMapper ledgers = mock(LedgerMapper.class);
    LedgerMemberMapper members = mock(LedgerMemberMapper.class);
    LedgerPersonMapper people = mock(LedgerPersonMapper.class);
    LedgerTransactionMapper transactions = mock(LedgerTransactionMapper.class);
    LedgerTransactionPersonMapper links = mock(LedgerTransactionPersonMapper.class);
    StatsServiceImpl service = new StatsServiceImpl(ledgers, members, people, transactions, links);
    Ledger ledger = new Ledger();
    @BeforeEach void setup() {
        for (Class<?> type : List.of(Ledger.class, LedgerMember.class, LedgerPerson.class, LedgerTransaction.class, LedgerTransactionPerson.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        ledger.setId(1L); ledger.setUuid("ledger"); ledger.setBaseCurrencyCode("USD"); ledger.setExchangeRateToCny(new BigDecimal("7"));
        when(ledgers.selectOne(any())).thenReturn(ledger); when(members.selectOne(any())).thenReturn(new LedgerMember());
    }
    @Test void legacyCnyAliasDoesNotMultiplyCnyTransactions() {
        ledger.setBaseCurrencyCode("CNY"); ledger.setExchangeRateToCny(new BigDecimal("7.2"));
        ledger.setCurrencyRatesToCny(java.util.Map.of("CNY",BigDecimal.ONE));
        when(transactions.selectList(any())).thenReturn(List.of(tx(1L,"CNY","100.00")));
        withLogin(() -> {
            var summary = assertDoesNotThrow(() -> service.summary("ledger"));
            assertEquals(new BigDecimal("100.00"),summary.getExpense());
            assertEquals(new BigDecimal("100.00"),service.categories("ledger").getFirst().getAmount());
        });
    }

    @Test void legacyUsdtStatisticsConvertWithoutChangingStoredCurrencyOrAmount() {
        ledger.setBaseCurrencyCode("USDT");
        var original = tx(1L,"USDT","100.00");
        when(transactions.selectList(any())).thenReturn(List.of(original));
        withLogin(() -> {
            var summary = assertDoesNotThrow(() -> service.summary("ledger"));
            assertEquals(new BigDecimal("700.00"),summary.getExpense());
            assertEquals(new BigDecimal("700.00"),service.categories("ledger").getFirst().getAmount());
        });
        assertEquals("USDT",original.getCurrencyCode());
        assertEquals(new BigDecimal("100.00"),original.getAmount());
    }

    @Test void mixedCurrencySummaryAndCategoriesConvertToCny() {
        when(transactions.selectList(any())).thenReturn(List.of(tx(1L,"USD","100"), tx(2L,"CNY","100")));
        withLogin(() -> {
            assertEquals("CNY", service.summary("ledger").getCurrencyCode());
            assertEquals("CNY", service.categories("ledger").getFirst().getCurrencyCode());
            assertEquals(new BigDecimal("800.00"), service.summary("ledger").getExpense());
            assertEquals(new BigDecimal("800.00"), service.categories("ledger").getFirst().getAmount());
        });
    }
    @Test void unknownCurrencyFailsInsteadOfAssumingOneToOne() {
        when(transactions.selectList(any())).thenReturn(List.of(tx(1L,"EUR","100")));
        withLogin(() -> assertThrows(BusinessException.class, () -> service.summary("ledger")));
    }
    @Test void splitConservesCentAndAssignsRemainderByUuid() {
        LedgerTransaction tx = tx(1L,"CNY","0.01"); tx.setPayerPersonId(2L);
        when(transactions.selectList(any())).thenReturn(List.of(tx));
        when(links.selectList(any())).thenReturn(List.of(link(3L), link(1L), link(2L)));
        when(people.selectList(any())).thenReturn(List.of(person(3L,"c"), person(2L,"b"), person(1L,"a")));
        withLogin(() -> {
            var balances = service.peopleBalances("ledger");
            assertEquals(new BigDecimal("0.00"), balances.stream().map(v -> v.getBalance()).reduce(BigDecimal.ZERO,BigDecimal::add));
            assertEquals(new BigDecimal("0.01"), balances.stream().filter(v -> v.getPersonUuid().equals("a")).findFirst().orElseThrow().getExpense());
        });
    }
    private LedgerTransaction tx(long id, String currency, String amount) { var t = new LedgerTransaction(); t.setId(id); t.setType(0); t.setAmount(new BigDecimal(amount)); t.setCurrencyCode(currency); t.setCategory("food"); return t; }
    private LedgerTransactionPerson link(long personId) { var l = new LedgerTransactionPerson(); l.setTransactionId(1L); l.setPersonId(personId); return l; }
    private LedgerPerson person(long id, String uuid) { var p = new LedgerPerson(); p.setId(id); p.setUuid(uuid); p.setName(uuid); return p; }
    private void withLogin(Runnable action) { try (MockedStatic<StpUtil> login=mockStatic(StpUtil.class)) { login.when(StpUtil::getLoginIdAsLong).thenReturn(7L); action.run(); } }
}

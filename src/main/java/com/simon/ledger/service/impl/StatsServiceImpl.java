package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.CategoryStatsResp;
import com.simon.ledger.dto.resp.PeopleBalanceResp;
import com.simon.ledger.dto.resp.StatsSummaryResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.LedgerTransactionPerson;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.LedgerTransactionMapper;
import com.simon.ledger.mapper.LedgerTransactionPersonMapper;
import com.simon.ledger.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final int MEMBER_STATUS_ACTIVE = 1;
    private static final int TYPE_EXPENSE = 0;
    private static final int TYPE_INCOME = 1;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final LedgerPersonMapper ledgerPersonMapper;
    private final LedgerTransactionMapper ledgerTransactionMapper;
    private final LedgerTransactionPersonMapper ledgerTransactionPersonMapper;

    @Override
    public StatsSummaryResp summary(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedgerAndMember(ledgerUuid, userId);
        List<LedgerTransaction> transactions = transactions(ledger.getId());

        BigDecimal expense = sumByType(transactions, TYPE_EXPENSE);
        BigDecimal income = sumByType(transactions, TYPE_INCOME);

        StatsSummaryResp resp = new StatsSummaryResp();
        resp.setLedgerUuid(ledger.getUuid());
        resp.setExpense(scale(expense));
        resp.setIncome(scale(income));
        resp.setBalance(scale(income.subtract(expense)));
        resp.setTransactionCount(transactions.size());
        return resp;
    }

    @Override
    public List<CategoryStatsResp> categories(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedgerAndMember(ledgerUuid, userId);
        Map<CategoryKey, List<LedgerTransaction>> groups = transactions(ledger.getId()).stream()
                .collect(Collectors.groupingBy(
                        transaction -> new CategoryKey(transaction.getCategory(), transaction.getType()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return groups.entrySet().stream()
                .map(entry -> {
                    CategoryStatsResp resp = new CategoryStatsResp();
                    resp.setCategory(entry.getKey().category());
                    resp.setType(entry.getKey().type());
                    resp.setAmount(scale(entry.getValue().stream()
                            .map(LedgerTransaction::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)));
                    resp.setTransactionCount(entry.getValue().size());
                    return resp;
                })
                .sorted(Comparator.comparing(CategoryStatsResp::getAmount).reversed())
                .toList();
    }

    @Override
    public List<PeopleBalanceResp> peopleBalances(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedgerAndMember(ledgerUuid, userId);
        List<LedgerTransaction> transactions = transactions(ledger.getId());
        Map<Long, List<Long>> transactionPersonIds = transactionPersonIds(transactions);
        Map<Long, LedgerPerson> personMap = personMap(transactionPersonIds);
        Map<Long, MutableBalance> balanceMap = new LinkedHashMap<>();

        for (LedgerTransaction transaction : transactions) {
            List<Long> personIds = transactionPersonIds.getOrDefault(transaction.getId(), List.of()).stream()
                    .filter(personMap::containsKey)
                    .toList();
            if (personIds.isEmpty()) {
                continue;
            }
            BigDecimal splitAmount = transaction.getAmount().divide(BigDecimal.valueOf(personIds.size()), 2, RoundingMode.HALF_UP);
            for (Long personId : personIds) {
                MutableBalance balance = balanceMap.computeIfAbsent(personId, ignored -> new MutableBalance());
                if (Objects.equals(transaction.getType(), TYPE_EXPENSE)) {
                    balance.expense = balance.expense.add(splitAmount);
                } else if (Objects.equals(transaction.getType(), TYPE_INCOME)) {
                    balance.income = balance.income.add(splitAmount);
                }
            }
        }

        return balanceMap.entrySet().stream()
                .map(entry -> {
                    LedgerPerson person = personMap.get(entry.getKey());
                    MutableBalance balance = entry.getValue();
                    PeopleBalanceResp resp = new PeopleBalanceResp();
                    resp.setPersonUuid(person.getUuid());
                    resp.setName(person.getName());
                    resp.setAvatar(person.getAvatar());
                    resp.setExpense(scale(balance.expense));
                    resp.setIncome(scale(balance.income));
                    resp.setBalance(scale(balance.income.subtract(balance.expense)));
                    return resp;
                })
                .sorted(Comparator.comparing(PeopleBalanceResp::getBalance).reversed())
                .toList();
    }

    private Ledger requireLedgerAndMember(String ledgerUuid, Long userId) {
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
        return ledger;
    }

    private List<LedgerTransaction> transactions(Long ledgerId) {
        return ledgerTransactionMapper.selectList(Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledgerId)
                .isNull(LedgerTransaction::getDeletedAt));
    }

    private BigDecimal sumByType(List<LedgerTransaction> transactions, int type) {
        return transactions.stream()
                .filter(transaction -> Objects.equals(transaction.getType(), type))
                .map(LedgerTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, List<Long>> transactionPersonIds(List<LedgerTransaction> transactions) {
        List<Long> transactionIds = transactions.stream().map(LedgerTransaction::getId).toList();
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        return ledgerTransactionPersonMapper.selectList(Wrappers.<LedgerTransactionPerson>lambdaQuery()
                        .in(LedgerTransactionPerson::getTransactionId, transactionIds))
                .stream()
                .collect(Collectors.groupingBy(
                        LedgerTransactionPerson::getTransactionId,
                        LinkedHashMap::new,
                        Collectors.mapping(LedgerTransactionPerson::getPersonId, Collectors.toList())
                ));
    }

    private Map<Long, LedgerPerson> personMap(Map<Long, List<Long>> transactionPersonIds) {
        List<Long> personIds = transactionPersonIds.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();
        if (personIds.isEmpty()) {
            return Map.of();
        }
        return ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery().in(LedgerPerson::getId, personIds))
                .stream()
                .collect(Collectors.toMap(LedgerPerson::getId, Function.identity(), (a, b) -> a));
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record CategoryKey(String category, Integer type) {
    }

    private static class MutableBalance {
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal income = BigDecimal.ZERO;
    }
}

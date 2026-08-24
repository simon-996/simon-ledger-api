package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.TransactionCreateReq;
import com.simon.ledger.dto.req.TransactionListReq;
import com.simon.ledger.dto.req.TransactionUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.PageResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.LedgerTransactionPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.LedgerTransactionMapper;
import com.simon.ledger.mapper.LedgerTransactionPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TransactionServiceImpl extends ServiceImpl<LedgerTransactionMapper, LedgerTransaction> implements TransactionService {

    private static final int MEMBER_STATUS_ACTIVE = 1;
    private static final int TYPE_EXPENSE = 0;
    private static final int TYPE_INCOME = 1;

    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final LedgerPersonMapper ledgerPersonMapper;
    private final LedgerTransactionPersonMapper ledgerTransactionPersonMapper;
    private final UserAccountMapper userAccountMapper;
    private final ChangeLogService changeLogService;

    @Override
    public PageResp<TransactionResp> list(String ledgerUuid, TransactionListReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        requireActiveMember(ledger.getId(), userId);

        LambdaQueryWrapper<LedgerTransaction> wrapper = Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledger.getId())
                .isNull(LedgerTransaction::getDeletedAt)
                .ge(req.getStartAt() != null, LedgerTransaction::getHappenedAt, req.getStartAt())
                .le(req.getEndAt() != null, LedgerTransaction::getHappenedAt, req.getEndAt())
                .eq(req.getType() != null, LedgerTransaction::getType, req.getType())
                .eq(StringUtils.hasText(req.getCategory()), LedgerTransaction::getCategory, normalize(req.getCategory()))
                .orderByDesc(LedgerTransaction::getHappenedAt)
                .orderByDesc(LedgerTransaction::getCreatedAt);

        if (StringUtils.hasText(req.getPersonUuid())) {
            LedgerPerson person = requirePerson(ledger.getId(), req.getPersonUuid(), true);
            List<Long> transactionIds = ledgerTransactionPersonMapper.selectList(Wrappers.<LedgerTransactionPerson>lambdaQuery()
                            .eq(LedgerTransactionPerson::getPersonId, person.getId()))
                    .stream()
                    .map(LedgerTransactionPerson::getTransactionId)
                    .toList();
            if (transactionIds.isEmpty()) {
                return emptyPage(req);
            }
            wrapper.in(LedgerTransaction::getId, transactionIds);
        }

        IPage<LedgerTransaction> page = page(new Page<>(page(req), pageSize(req)), wrapper);
        List<TransactionResp> records = toRespList(ledger, page.getRecords());

        PageResp<TransactionResp> resp = new PageResp<>();
        resp.setPage(page.getCurrent());
        resp.setPageSize(page.getSize());
        resp.setTotal(page.getTotal());
        resp.setRecords(records);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionResp create(String ledgerUuid, TransactionCreateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        if (!LedgerRoles.canCreateTransaction(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        validateType(req.getType());
        LedgerTransaction exists = findByClientOperationId(ledger.getId(), userId, req.getClientOperationId());
        if (exists != null) {
            Map<Long, UserAccount> lockedUsers = lockResponseUsers(exists);
            LockedPeople lockedPeople = lockCurrentTransactionPeople(exists);
            return toResp(
                    ledger,
                    exists,
                    lockedPeople.people(),
                    lockedUsers,
                    payerPersonUuidMap(lockedPeople.payer()));
        }
        Map<Long, UserAccount> lockedUsers = lockUsers(Stream.of(userId));
        LockedPeople lockedPeople = lockRequestedPeople(
                ledger.getId(), req.getType(), req.getPayerPersonUuid(), req.getPersonUuids());
        List<LedgerPerson> people = lockedPeople.people();
        LedgerPerson payer = lockedPeople.payer();

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setUuid(IdUtil.fastSimpleUUID());
        transaction.setLedgerId(ledger.getId());
        transaction.setType(req.getType());
        transaction.setPayerPersonId(payer == null ? null : payer.getId());
        transaction.setAmount(req.getAmount());
        transaction.setCurrencyCode(req.getCurrencyCode().trim().toUpperCase());
        transaction.setCategory(req.getCategory().trim());
        transaction.setNote(normalize(req.getNote()));
        transaction.setCreatedByUserId(userId);
        transaction.setLastModifiedByUserId(null);
        transaction.setClientOperationId(req.getClientOperationId().trim());
        transaction.setVersion(1);
        transaction.setHappenedAt(req.getHappenedAt());
        save(transaction);

        replacePeople(transaction.getId(), people);
        changeLogService.record(ledger.getId(), "transaction", transaction.getUuid(), "create", userId);
        return toResp(ledger, transaction, people, lockedUsers, payerPersonUuidMap(payer));
    }

    @Override
    public TransactionResp detail(String ledgerUuid, String transactionUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        requireActiveMember(ledger.getId(), userId);
        LedgerTransaction transaction = requireTransaction(ledger.getId(), transactionUuid);
        return toResp(ledger, transaction, peopleByTransactionId(transaction.getId(), true), userMap(List.of(transaction)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionResp update(String ledgerUuid, String transactionUuid, TransactionUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        LedgerTransaction transaction = requireTransactionForMutation(ledger.getId(), transactionUuid);
        requireEditPermission(member, transaction, userId);
        if (transaction.getDeletedAt() != null || !Objects.equals(transaction.getVersion(), req.getVersion())) {
            throw transactionConflict(ledger, transaction, req.getVersion(), null);
        }
        validateType(req.getType());
        Map<Long, UserAccount> lockedUsers = lockMutationUsers(transaction, userId);
        LockedPeople lockedPeople = lockRequestedPeople(
                ledger.getId(), req.getType(), req.getPayerPersonUuid(), req.getPersonUuids());
        List<LedgerPerson> people = lockedPeople.people();
        LedgerPerson payer = lockedPeople.payer();

        Long payerPersonId = payer == null ? null : payer.getId();
        String currencyCode = req.getCurrencyCode().trim().toUpperCase();
        String category = req.getCategory().trim();
        String note = normalize(req.getNote());
        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerTransaction>lambdaUpdate()
                .eq(LedgerTransaction::getId, transaction.getId())
                .eq(LedgerTransaction::getVersion, req.getVersion())
                .isNull(LedgerTransaction::getDeletedAt)
                .set(LedgerTransaction::getType, req.getType())
                .set(LedgerTransaction::getPayerPersonId, payerPersonId)
                .set(LedgerTransaction::getAmount, req.getAmount())
                .set(LedgerTransaction::getCurrencyCode, currencyCode)
                .set(LedgerTransaction::getCategory, category)
                .set(LedgerTransaction::getNote, note)
                .set(LedgerTransaction::getHappenedAt, req.getHappenedAt())
                .set(LedgerTransaction::getLastModifiedByUserId, userId)
                .set(LedgerTransaction::getDeletedAt, null)
                .set(LedgerTransaction::getUpdatedAt, updatedAt)
                .set(LedgerTransaction::getVersion, nextVersion));
        if (affected == 0) {
            LedgerTransaction latest = reloadTransactionForUpdate(transaction.getId(), transactionUuid);
            requireEditPermission(member, latest, userId);
            throw transactionConflict(ledger, latest, req.getVersion(), lockedUsers);
        }
        applyUpdate(transaction, req, payerPersonId, userId, currencyCode, category, note, updatedAt, nextVersion);

        replacePeople(transaction.getId(), people);
        changeLogService.record(ledger.getId(), "transaction", transaction.getUuid(), "update", userId);
        return toResp(ledger, transaction, people, lockedUsers, payerPersonUuidMap(payer));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VersionMutationResp delete(String ledgerUuid, String transactionUuid, VersionDeleteReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        LedgerTransaction transaction = requireTransactionForMutation(ledger.getId(), transactionUuid);
        requireEditPermission(member, transaction, userId);
        if (transaction.getDeletedAt() != null || !Objects.equals(transaction.getVersion(), req.getVersion())) {
            throw transactionConflict(ledger, transaction, req.getVersion(), null);
        }

        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerTransaction>lambdaUpdate()
                .eq(LedgerTransaction::getId, transaction.getId())
                .eq(LedgerTransaction::getVersion, req.getVersion())
                .isNull(LedgerTransaction::getDeletedAt)
                .set(LedgerTransaction::getDeletedAt, updatedAt)
                .set(LedgerTransaction::getLastModifiedByUserId, userId)
                .set(LedgerTransaction::getUpdatedAt, updatedAt)
                .set(LedgerTransaction::getVersion, nextVersion));
        if (affected == 0) {
            LedgerTransaction latest = reloadTransactionForUpdate(transaction.getId(), transactionUuid);
            requireEditPermission(member, latest, userId);
            throw transactionConflict(ledger, latest, req.getVersion(), null);
        }
        transaction.setDeletedAt(updatedAt);
        transaction.setLastModifiedByUserId(userId);
        transaction.setUpdatedAt(updatedAt);
        transaction.setVersion(nextVersion);
        changeLogService.record(ledger.getId(), "transaction", transaction.getUuid(), "delete", userId);
        return new VersionMutationResp(transaction.getUuid(), nextVersion, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionResp restore(String ledgerUuid, String transactionUuid, TransactionUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        LedgerTransaction transaction = requireTransactionForMutation(ledger.getId(), transactionUuid);
        requireEditPermission(member, transaction, userId);
        if (transaction.getDeletedAt() == null || !Objects.equals(transaction.getVersion(), req.getVersion())) {
            throw transactionConflict(ledger, transaction, req.getVersion(), null);
        }
        validateType(req.getType());
        Map<Long, UserAccount> lockedUsers = lockMutationUsers(transaction, userId);
        LockedPeople lockedPeople = lockRequestedPeople(
                ledger.getId(), req.getType(), req.getPayerPersonUuid(), req.getPersonUuids());
        List<LedgerPerson> people = lockedPeople.people();
        LedgerPerson payer = lockedPeople.payer();

        Long payerPersonId = payer == null ? null : payer.getId();
        String currencyCode = req.getCurrencyCode().trim().toUpperCase();
        String category = req.getCategory().trim();
        String note = normalize(req.getNote());
        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerTransaction>lambdaUpdate()
                .eq(LedgerTransaction::getId, transaction.getId())
                .eq(LedgerTransaction::getVersion, req.getVersion())
                .isNotNull(LedgerTransaction::getDeletedAt)
                .set(LedgerTransaction::getType, req.getType())
                .set(LedgerTransaction::getPayerPersonId, payerPersonId)
                .set(LedgerTransaction::getAmount, req.getAmount())
                .set(LedgerTransaction::getCurrencyCode, currencyCode)
                .set(LedgerTransaction::getCategory, category)
                .set(LedgerTransaction::getNote, note)
                .set(LedgerTransaction::getHappenedAt, req.getHappenedAt())
                .set(LedgerTransaction::getLastModifiedByUserId, userId)
                .set(LedgerTransaction::getDeletedAt, null)
                .set(LedgerTransaction::getUpdatedAt, updatedAt)
                .set(LedgerTransaction::getVersion, nextVersion));
        if (affected == 0) {
            LedgerTransaction latest = reloadTransactionForUpdate(transaction.getId(), transactionUuid);
            requireEditPermission(member, latest, userId);
            throw transactionConflict(ledger, latest, req.getVersion(), lockedUsers);
        }
        applyUpdate(transaction, req, payerPersonId, userId, currencyCode, category, note, updatedAt, nextVersion);
        transaction.setDeletedAt(null);
        replacePeople(transaction.getId(), people);
        changeLogService.record(ledger.getId(), "transaction", transaction.getUuid(), "update", userId);
        return toResp(ledger, transaction, people, lockedUsers, payerPersonUuidMap(payer));
    }

    private LedgerTransaction findByClientOperationId(Long ledgerId, Long userId, String clientOperationId) {
        if (!StringUtils.hasText(clientOperationId)) {
            return null;
        }
        return baseMapper.selectOne(Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledgerId)
                .eq(LedgerTransaction::getCreatedByUserId, userId)
                .eq(LedgerTransaction::getClientOperationId, clientOperationId.trim())
                .isNull(LedgerTransaction::getDeletedAt)
                .last("FOR UPDATE"));
    }

    private Ledger requireLedger(String ledgerUuid) {
        Ledger ledger = ledgerMapper.selectOne(Wrappers.<Ledger>lambdaQuery()
                .eq(Ledger::getUuid, ledgerUuid)
                .isNull(Ledger::getDeletedAt));
        if (ledger == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }
        return ledger;
    }

    private LedgerMember requireActiveMember(Long ledgerId, Long userId) {
        LedgerMember member = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt));
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    private LedgerMember requireActiveMemberForUpdate(Long ledgerId, Long userId) {
        LedgerMember member = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .last("FOR UPDATE"));
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    private LedgerTransaction requireTransaction(Long ledgerId, String transactionUuid) {
        LedgerTransaction transaction = lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledgerId)
                .eq(LedgerTransaction::getUuid, transactionUuid)
                .isNull(LedgerTransaction::getDeletedAt)
                .one();
        if (transaction == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "流水不存在");
        }
        return transaction;
    }

    private LedgerTransaction requireTransactionForMutation(Long ledgerId, String transactionUuid) {
        LedgerTransaction transaction = baseMapper.selectOne(Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledgerId)
                .eq(LedgerTransaction::getUuid, transactionUuid)
                .last("FOR UPDATE"));
        if (transaction == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "流水不存在");
        }
        return transaction;
    }

    private LedgerTransaction reloadTransactionForUpdate(Long transactionId, String transactionUuid) {
        LedgerTransaction latest = baseMapper.selectOne(Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getId, transactionId)
                .last("FOR UPDATE"));
        if (latest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "流水不存在: " + transactionUuid);
        }
        return latest;
    }

    private LedgerPerson requirePerson(Long ledgerId, String personUuid, boolean includeDeleted) {
        LambdaQueryWrapper<LedgerPerson> wrapper = Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .eq(LedgerPerson::getUuid, personUuid);
        if (!includeDeleted) {
            wrapper.isNull(LedgerPerson::getDeletedAt);
        }
        LedgerPerson person = ledgerPersonMapper.selectOne(wrapper);
        if (person == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参与人不存在");
        }
        return person;
    }

    private LockedPeople lockRequestedPeople(
            Long ledgerId,
            Integer type,
            String payerPersonUuid,
            List<String> personUuids
    ) {
        List<String> participantUuids = normalizedPersonUuids(personUuids);
        if (participantUuids.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参与人不能为空");
        }
        String normalizedPayerUuid = Objects.equals(TYPE_EXPENSE, type) && StringUtils.hasText(payerPersonUuid)
                ? payerPersonUuid.trim()
                : null;
        List<String> lockedUuids = Stream.concat(
                        participantUuids.stream(),
                        normalizedPayerUuid == null ? Stream.empty() : Stream.of(normalizedPayerUuid))
                .distinct()
                .toList();
        List<LedgerPerson> lockedPeople = ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .in(LedgerPerson::getUuid, lockedUuids)
                .isNull(LedgerPerson::getDeletedAt)
                .orderByAsc(LedgerPerson::getId)
                .last("FOR UPDATE"));
        Map<String, LedgerPerson> peopleByUuid = lockedPeople.stream()
                .collect(Collectors.toMap(LedgerPerson::getUuid, Function.identity(), (a, b) -> a));
        List<LedgerPerson> participants = participantUuids.stream()
                .map(peopleByUuid::get)
                .filter(Objects::nonNull)
                .toList();
        if (participants.size() != participantUuids.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参与人不存在或已删除");
        }
        LedgerPerson payer = normalizedPayerUuid == null ? null : peopleByUuid.get(normalizedPayerUuid);
        if (normalizedPayerUuid != null && payer == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参与人不存在");
        }
        return new LockedPeople(participants, payer);
    }

    private List<String> normalizedPersonUuids(List<String> personUuids) {
        return personUuids.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void replacePeople(Long transactionId, List<LedgerPerson> people) {
        ledgerTransactionPersonMapper.delete(Wrappers.<LedgerTransactionPerson>lambdaQuery()
                .eq(LedgerTransactionPerson::getTransactionId, transactionId));
        LocalDateTime now = LocalDateTime.now();
        for (LedgerPerson person : people) {
            LedgerTransactionPerson relation = new LedgerTransactionPerson();
            relation.setTransactionId(transactionId);
            relation.setPersonId(person.getId());
            relation.setCreatedAt(now);
            ledgerTransactionPersonMapper.insert(relation);
        }
    }

    private List<LedgerPerson> peopleByTransactionId(Long transactionId, boolean includeDeleted) {
        List<Long> personIds = ledgerTransactionPersonMapper.selectList(Wrappers.<LedgerTransactionPerson>lambdaQuery()
                        .eq(LedgerTransactionPerson::getTransactionId, transactionId))
                .stream()
                .map(LedgerTransactionPerson::getPersonId)
                .toList();
        if (personIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<LedgerPerson> wrapper = Wrappers.<LedgerPerson>lambdaQuery()
                .in(LedgerPerson::getId, personIds);
        if (!includeDeleted) {
            wrapper.isNull(LedgerPerson::getDeletedAt);
        }
        return ledgerPersonMapper.selectList(wrapper);
    }

    private LockedPeople lockCurrentTransactionPeople(LedgerTransaction transaction) {
        List<LedgerTransactionPerson> relations = ledgerTransactionPersonMapper.selectList(
                Wrappers.<LedgerTransactionPerson>lambdaQuery()
                        .eq(LedgerTransactionPerson::getTransactionId, transaction.getId())
                        .orderByAsc(LedgerTransactionPerson::getId)
                        .last("FOR UPDATE"));
        List<Long> personIds = Stream.concat(
                        relations.stream().map(LedgerTransactionPerson::getPersonId),
                        transaction.getPayerPersonId() == null
                                ? Stream.empty()
                                : Stream.of(transaction.getPayerPersonId()))
                .distinct()
                .sorted()
                .toList();
        if (personIds.isEmpty()) {
            return new LockedPeople(List.of(), null);
        }
        Map<Long, LedgerPerson> peopleById = ledgerPersonMapper.selectList(
                        Wrappers.<LedgerPerson>lambdaQuery()
                                .in(LedgerPerson::getId, personIds)
                                .orderByAsc(LedgerPerson::getId)
                                .last("FOR UPDATE"))
                .stream()
                .collect(Collectors.toMap(LedgerPerson::getId, Function.identity(), (a, b) -> a));
        List<LedgerPerson> people = relations.stream()
                .map(relation -> peopleById.get(relation.getPersonId()))
                .filter(Objects::nonNull)
                .toList();
        return new LockedPeople(people, peopleById.get(transaction.getPayerPersonId()));
    }

    private Map<Long, List<LedgerPerson>> peopleMapByTransactionIds(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        List<LedgerTransactionPerson> relations = ledgerTransactionPersonMapper.selectList(Wrappers.<LedgerTransactionPerson>lambdaQuery()
                .in(LedgerTransactionPerson::getTransactionId, transactionIds));
        List<Long> personIds = relations.stream()
                .map(LedgerTransactionPerson::getPersonId)
                .distinct()
                .toList();
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LedgerPerson> personMap = ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery()
                        .in(LedgerPerson::getId, personIds))
                .stream()
                .collect(Collectors.toMap(LedgerPerson::getId, Function.identity(), (a, b) -> a));

        return relations.stream()
                .filter(relation -> personMap.containsKey(relation.getPersonId()))
                .collect(Collectors.groupingBy(
                        LedgerTransactionPerson::getTransactionId,
                        LinkedHashMap::new,
                        Collectors.mapping(relation -> personMap.get(relation.getPersonId()), Collectors.toList())
                ));
    }

    private List<TransactionResp> toRespList(Ledger ledger, List<LedgerTransaction> transactions) {
        List<Long> transactionIds = transactions.stream().map(LedgerTransaction::getId).toList();
        Map<Long, List<LedgerPerson>> peopleMap = peopleMapByTransactionIds(transactionIds);
        Map<Long, String> payerPersonUuidMap = payerPersonUuidMap(transactions);
        Map<Long, UserAccount> userMap = userMap(transactions);
        return transactions.stream()
                .map(transaction -> toResp(ledger, transaction, peopleMap.getOrDefault(transaction.getId(), List.of()), userMap, payerPersonUuidMap))
                .toList();
    }

    private TransactionResp toResp(
            Ledger ledger,
            LedgerTransaction transaction,
            List<LedgerPerson> people,
            Map<Long, UserAccount> userMap
    ) {
        return toResp(ledger, transaction, people, userMap, payerPersonUuidMap(List.of(transaction)));
    }

    private TransactionResp toResp(
            Ledger ledger,
            LedgerTransaction transaction,
            List<LedgerPerson> people,
            Map<Long, UserAccount> userMap,
            Map<Long, String> payerPersonUuidMap
    ) {
        TransactionResp resp = new TransactionResp();
        resp.setUuid(transaction.getUuid());
        resp.setLedgerUuid(ledger.getUuid());
        resp.setType(transaction.getType());
        resp.setPayerPersonUuid(payerPersonUuid(transaction, payerPersonUuidMap));
        resp.setAmount(transaction.getAmount());
        resp.setCurrencyCode(transaction.getCurrencyCode());
        resp.setCategory(transaction.getCategory());
        resp.setNote(transaction.getNote());
        resp.setCreatedByUserUuid(userUuid(userMap, transaction.getCreatedByUserId()));
        resp.setCreatedByNickname(userNickname(userMap, transaction.getCreatedByUserId()));
        resp.setCreatedByAvatar(userAvatar(userMap, transaction.getCreatedByUserId()));
        resp.setLastModifiedByUserUuid(userUuid(userMap, transaction.getLastModifiedByUserId()));
        resp.setLastModifiedByNickname(userNickname(userMap, transaction.getLastModifiedByUserId()));
        resp.setLastModifiedByAvatar(userAvatar(userMap, transaction.getLastModifiedByUserId()));
        resp.setClientOperationId(transaction.getClientOperationId());
        resp.setVersion(transaction.getVersion());
        resp.setHappenedAt(transaction.getHappenedAt());
        resp.setCreatedAt(transaction.getCreatedAt());
        resp.setUpdatedAt(transaction.getUpdatedAt());
        resp.setPersonUuids(people.stream().map(LedgerPerson::getUuid).toList());
        return resp;
    }

    private Map<Long, UserAccount> userMap(List<LedgerTransaction> transactions) {
        List<Long> userIds = transactions.stream()
                .flatMap(transaction -> Stream.of(transaction.getCreatedByUserId(), transaction.getLastModifiedByUserId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, UserAccount> lockMutationUsers(LedgerTransaction transaction, Long userId) {
        return lockUsers(Stream.of(
                transaction.getCreatedByUserId(), transaction.getLastModifiedByUserId(), userId));
    }

    private Map<Long, UserAccount> lockResponseUsers(LedgerTransaction transaction) {
        return lockUsers(Stream.of(
                transaction.getCreatedByUserId(), transaction.getLastModifiedByUserId()));
    }

    private Map<Long, UserAccount> lockUsers(Stream<Long> requestedUserIds) {
        List<Long> userIds = requestedUserIds
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery()
                        .in(UserAccount::getId, userIds)
                        .orderByAsc(UserAccount::getId)
                        .last("FOR UPDATE"))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private String userUuid(Map<Long, UserAccount> userMap, Long userId) {
        UserAccount user = userMap.get(userId);
        return user == null ? null : user.getUuid();
    }

    private String userNickname(Map<Long, UserAccount> userMap, Long userId) {
        UserAccount user = userMap.get(userId);
        return user == null ? null : user.getNickname();
    }

    private String userAvatar(Map<Long, UserAccount> userMap, Long userId) {
        UserAccount user = userMap.get(userId);
        return user == null ? null : user.getAvatar();
    }

    private Map<Long, String> payerPersonUuidMap(List<LedgerTransaction> transactions) {
        List<Long> payerPersonIds = transactions.stream()
                .map(LedgerTransaction::getPayerPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (payerPersonIds.isEmpty()) {
            return Map.of();
        }
        return ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery().in(LedgerPerson::getId, payerPersonIds))
                .stream()
                .collect(Collectors.toMap(LedgerPerson::getId, LedgerPerson::getUuid, (a, b) -> a));
    }

    private Map<Long, String> payerPersonUuidMap(LedgerPerson payer) {
        if (payer == null) {
            return Map.of();
        }
        return Map.of(payer.getId(), payer.getUuid());
    }

    private String payerPersonUuid(LedgerTransaction transaction, Map<Long, String> payerPersonUuidMap) {
        Long payerPersonId = transaction.getPayerPersonId();
        if (payerPersonId == null) {
            return null;
        }
        return payerPersonUuidMap.get(payerPersonId);
    }

    private void requireEditPermission(LedgerMember member, LedgerTransaction transaction, Long userId) {
        if (LedgerRoles.canEditAnyTransaction(member.getRole())) {
            return;
        }
        if (LedgerRoles.EDITOR.equals(member.getRole()) && Objects.equals(transaction.getCreatedByUserId(), userId)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void applyUpdate(
            LedgerTransaction transaction,
            TransactionUpdateReq req,
            Long payerPersonId,
            Long userId,
            String currencyCode,
            String category,
            String note,
            LocalDateTime updatedAt,
            int nextVersion
    ) {
        transaction.setType(req.getType());
        transaction.setPayerPersonId(payerPersonId);
        transaction.setAmount(req.getAmount());
        transaction.setCurrencyCode(currencyCode);
        transaction.setCategory(category);
        transaction.setNote(note);
        transaction.setHappenedAt(req.getHappenedAt());
        transaction.setLastModifiedByUserId(userId);
        transaction.setUpdatedAt(updatedAt);
        transaction.setVersion(nextVersion);
    }

    private VersionConflictException transactionConflict(
            Ledger ledger,
            LedgerTransaction transaction,
            Integer submittedVersion,
            Map<Long, UserAccount> prelockedUsers
    ) {
        Map<Long, UserAccount> lockedUsers = prelockedUsers == null
                ? lockResponseUsers(transaction)
                : prelockedUsers;
        LockedPeople lockedPeople = lockCurrentTransactionPeople(transaction);
        TransactionResp snapshot = toResp(
                ledger,
                transaction,
                lockedPeople.people(),
                lockedUsers,
                payerPersonUuidMap(lockedPeople.payer()));
        return new VersionConflictException(new ConflictResp(
                "transaction",
                transaction.getUuid(),
                submittedVersion,
                transaction.getVersion(),
                transaction.getDeletedAt() != null,
                snapshot
        ));
    }

    private void validateType(Integer type) {
        if (!Objects.equals(TYPE_EXPENSE, type) && !Objects.equals(TYPE_INCOME, type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "流水类型不正确");
        }
    }

    private PageResp<TransactionResp> emptyPage(TransactionListReq req) {
        PageResp<TransactionResp> resp = new PageResp<>();
        resp.setPage(page(req));
        resp.setPageSize(pageSize(req));
        resp.setTotal(0);
        resp.setRecords(List.of());
        return resp;
    }

    private long page(TransactionListReq req) {
        if (req.getPage() == null || req.getPage() < 1) {
            return 1;
        }
        return req.getPage();
    }

    private long pageSize(TransactionListReq req) {
        if (req.getPageSize() == null || req.getPageSize() < 1) {
            return 20;
        }
        return Math.min(req.getPageSize(), 100);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private record LockedPeople(List<LedgerPerson> people, LedgerPerson payer) {
    }
}

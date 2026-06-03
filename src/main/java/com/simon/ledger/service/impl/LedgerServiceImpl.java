package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.LedgerCreateReq;
import com.simon.ledger.dto.req.LedgerCreateWithPeopleReq;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.req.PersonCreateReq;
import com.simon.ledger.dto.resp.LedgerMemberSummaryResp;
import com.simon.ledger.dto.resp.LedgerCreateWithPeopleResp;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.LedgerService;
import com.simon.ledger.service.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LedgerServiceImpl extends ServiceImpl<LedgerMapper, Ledger> implements LedgerService {

    private static final int MEMBER_STATUS_ACTIVE = 1;

    private final LedgerMemberMapper ledgerMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final ChangeLogService changeLogService;
    private final PersonService personService;

    @Override
    public List<LedgerResp> listMine() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<LedgerMember> members = activeMembersByUserId(userId);
        if (members.isEmpty()) {
            return List.of();
        }

        Map<Long, LedgerMember> memberMap = members.stream()
                .collect(Collectors.toMap(LedgerMember::getLedgerId, Function.identity(), (a, b) -> a));
        List<Long> ledgerIds = members.stream().map(LedgerMember::getLedgerId).toList();

        List<Ledger> ledgers = lambdaQuery()
                .in(Ledger::getId, ledgerIds)
                .isNull(Ledger::getDeletedAt)
                .list()
                .stream()
                .sorted(Comparator.comparing(Ledger::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        Map<Long, List<LedgerMemberSummaryResp>> membersMap = membersMap(ledgerIds);
        return ledgers.stream()
                .map(ledger -> toResp(ledger, memberMap.get(ledger.getId()).getRole(), membersMap.getOrDefault(ledger.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LedgerResp create(LedgerCreateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();

        Ledger ledger = new Ledger();
        ledger.setUuid(IdUtil.fastSimpleUUID());
        ledger.setName(req.getName().trim());
        ledger.setBaseCurrencyCode(req.getBaseCurrencyCode().trim().toUpperCase());
        ledger.setExchangeRateToCny(req.getExchangeRateToCny());
        ledger.setOwnerUserId(userId);
        save(ledger);

        LedgerMember member = new LedgerMember();
        member.setUuid(IdUtil.fastSimpleUUID());
        member.setLedgerId(ledger.getId());
        member.setUserId(userId);
        member.setRole(LedgerRoles.OWNER);
        member.setStatus(MEMBER_STATUS_ACTIVE);
        member.setJoinedAt(LocalDateTime.now());
        ledgerMemberMapper.insert(member);
        changeLogService.record(ledger.getId(), "ledger", ledger.getUuid(), "create", userId);

        return toResp(ledger, LedgerRoles.OWNER, List.of(toMemberSummary(member, userAccountMapper.selectById(userId))));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LedgerCreateWithPeopleResp createWithPeople(LedgerCreateWithPeopleReq req) {
        LedgerCreateReq ledgerReq = new LedgerCreateReq();
        ledgerReq.setName(req.getName());
        ledgerReq.setBaseCurrencyCode(req.getBaseCurrencyCode());
        ledgerReq.setExchangeRateToCny(req.getExchangeRateToCny());

        LedgerResp ledger = create(ledgerReq);
        List<PersonResp> people = (req.getPeople() == null ? List.<PersonCreateReq>of() : req.getPeople())
                .stream()
                .map(personReq -> personService.create(ledger.getUuid(), personReq))
                .toList();

        LedgerCreateWithPeopleResp resp = new LedgerCreateWithPeopleResp();
        resp.setLedger(ledger);
        resp.setPeople(people);
        return resp;
    }

    @Override
    public LedgerResp detail(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        return toResp(ledger, member.getRole(), membersMap(List.of(ledger.getId())).getOrDefault(ledger.getId(), List.of()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LedgerResp update(String ledgerUuid, LedgerUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        if (!LedgerRoles.canManageLedger(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        ledger.setName(req.getName().trim());
        ledger.setBaseCurrencyCode(req.getBaseCurrencyCode().trim().toUpperCase());
        ledger.setExchangeRateToCny(req.getExchangeRateToCny());
        updateById(ledger);
        changeLogService.record(ledger.getId(), "ledger", ledger.getUuid(), "update", userId);
        return toResp(ledger, member.getRole(), membersMap(List.of(ledger.getId())).getOrDefault(ledger.getId(), List.of()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        if (!LedgerRoles.isOwner(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        ledger.setDeletedAt(LocalDateTime.now());
        updateById(ledger);
        changeLogService.record(ledger.getId(), "ledger", ledger.getUuid(), "delete", userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leave(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = ledgerMemberMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE));
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (member.getDeletedAt() != null) {
            return;
        }
        if (LedgerRoles.isOwner(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "owner 不能退出账本");
        }

        member.setDeletedAt(LocalDateTime.now());
        ledgerMemberMapper.updateById(member);
        changeLogService.record(ledger.getId(), "member", member.getUuid(), "delete", userId);
    }

    private List<LedgerMember> activeMembersByUserId(Long userId) {
        return ledgerMemberMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt));
    }

    private Ledger requireLedger(String ledgerUuid) {
        Ledger ledger = lambdaQuery()
                .eq(Ledger::getUuid, ledgerUuid)
                .isNull(Ledger::getDeletedAt)
                .one();
        if (ledger == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }
        return ledger;
    }

    private LedgerMember requireActiveMember(Long ledgerId, Long userId) {
        LedgerMember member = ledgerMemberMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt));
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    private Map<Long, List<LedgerMemberSummaryResp>> membersMap(List<Long> ledgerIds) {
        if (ledgerIds.isEmpty()) {
            return Map.of();
        }
        List<LedgerMember> members = ledgerMemberMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<LedgerMember>lambdaQuery()
                .in(LedgerMember::getLedgerId, ledgerIds)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .orderByAsc(LedgerMember::getJoinedAt));
        Map<Long, UserAccount> userMap = userMap(members);
        return members.stream()
                .collect(Collectors.groupingBy(
                        LedgerMember::getLedgerId,
                        Collectors.mapping(member -> toMemberSummary(member, userMap.get(member.getUserId())), Collectors.toList())
                ));
    }

    private Map<Long, UserAccount> userMap(List<LedgerMember> members) {
        List<Long> userIds = members.stream()
                .map(LedgerMember::getUserId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private LedgerMemberSummaryResp toMemberSummary(LedgerMember member, UserAccount user) {
        LedgerMemberSummaryResp resp = new LedgerMemberSummaryResp();
        resp.setUuid(member.getUuid());
        resp.setUserUuid(user == null ? null : user.getUuid());
        resp.setNickname(user == null ? null : user.getNickname());
        resp.setAvatar(user == null ? null : user.getAvatar());
        resp.setRole(member.getRole());
        return resp;
    }

    private LedgerResp toResp(Ledger ledger, String role, List<LedgerMemberSummaryResp> members) {
        LedgerResp resp = new LedgerResp();
        resp.setUuid(ledger.getUuid());
        resp.setName(ledger.getName());
        resp.setBaseCurrencyCode(ledger.getBaseCurrencyCode());
        resp.setExchangeRateToCny(ledger.getExchangeRateToCny());
        resp.setRole(role);
        resp.setMemberCount(members.size());
        resp.setMembers(members);
        resp.setCreatedAt(ledger.getCreatedAt());
        resp.setUpdatedAt(ledger.getUpdatedAt());
        return resp;
    }
}

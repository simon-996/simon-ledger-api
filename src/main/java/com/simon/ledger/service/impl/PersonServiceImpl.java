package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.PersonCreateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonServiceImpl extends ServiceImpl<LedgerPersonMapper, LedgerPerson> implements PersonService {

    private static final int MEMBER_STATUS_ACTIVE = 1;
    private static final String DEFAULT_AVATAR = "";

    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final ChangeLogService changeLogService;

    @Override
    public List<PersonResp> list(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        requireActiveMember(ledger.getId(), userId);

        List<LedgerPerson> people = lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledger.getId())
                .isNull(LedgerPerson::getDeletedAt)
                .orderByAsc(LedgerPerson::getCreatedAt)
                .list();
        Map<Long, UserAccount> linkedUserMap = linkedUserMap(people);
        return people.stream()
                .map(person -> toResp(ledger, person, linkedUserMap.get(person.getLinkedUserId())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonResp create(String ledgerUuid, PersonCreateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        requireManagePeoplePermission(member);

        UserAccount linkedUser = findLinkedUser(req.getLinkedUserUuid());
        if (linkedUser != null) {
            ensureLinkedUserNotBound(ledger.getId(), linkedUser.getId(), null);
        } else {
            ensureManualNameNotUsed(ledger.getId(), req.getName(), null);
        }

        LedgerPerson person = new LedgerPerson();
        person.setUuid(IdUtil.fastSimpleUUID());
        person.setLedgerId(ledger.getId());
        person.setLinkedUserId(linkedUser == null ? null : linkedUser.getId());
        person.setName(req.getName().trim());
        person.setAvatar(normalizeAvatar(req.getAvatar()));
        save(person);
        changeLogService.record(ledger.getId(), "person", person.getUuid(), "create", userId);

        return toResp(ledger, person, linkedUser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonResp update(String ledgerUuid, String personUuid, PersonUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        requireManagePeoplePermission(member);
        LedgerPerson person = requirePerson(ledger.getId(), personUuid);

        UserAccount linkedUser = findLinkedUser(req.getLinkedUserUuid());
        if (linkedUser != null) {
            ensureLinkedUserNotBound(ledger.getId(), linkedUser.getId(), person.getId());
        } else {
            ensureManualNameNotUsed(ledger.getId(), req.getName(), person.getId());
        }

        person.setLinkedUserId(linkedUser == null ? null : linkedUser.getId());
        person.setName(req.getName().trim());
        person.setAvatar(normalizeAvatar(req.getAvatar()));
        updateById(person);
        changeLogService.record(ledger.getId(), "person", person.getUuid(), "update", userId);

        return toResp(ledger, person, linkedUser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String ledgerUuid, String personUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        requireManagePeoplePermission(member);
        LedgerPerson person = requirePerson(ledger.getId(), personUuid);

        person.setDeletedAt(LocalDateTime.now());
        updateById(person);
        changeLogService.record(ledger.getId(), "person", person.getUuid(), "delete", userId);
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

    private LedgerPerson requirePerson(Long ledgerId, String personUuid) {
        LedgerPerson person = lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .eq(LedgerPerson::getUuid, personUuid)
                .isNull(LedgerPerson::getDeletedAt)
                .one();
        if (person == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参与人不存在");
        }
        return person;
    }

    private void requireManagePeoplePermission(LedgerMember member) {
        if (!LedgerRoles.canManageLedger(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private UserAccount findLinkedUser(String linkedUserUuid) {
        if (!StringUtils.hasText(linkedUserUuid)) {
            return null;
        }
        UserAccount user = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUuid, linkedUserUuid.trim())
                .isNull(UserAccount::getDeletedAt));
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "绑定用户不存在");
        }
        return user;
    }

    private void ensureLinkedUserNotBound(Long ledgerId, Long linkedUserId, Long currentPersonId) {
        LedgerPerson exists = lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .eq(LedgerPerson::getLinkedUserId, linkedUserId)
                .isNull(LedgerPerson::getDeletedAt)
                .one();
        if (exists != null && !Objects.equals(exists.getId(), currentPersonId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该用户已绑定到账本参与人");
        }
    }

    private void ensureManualNameNotUsed(Long ledgerId, String name, Long currentPersonId) {
        String normalizedName = name == null ? "" : name.trim();
        LedgerPerson exists = lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .isNull(LedgerPerson::getLinkedUserId)
                .eq(LedgerPerson::getName, normalizedName)
                .isNull(LedgerPerson::getDeletedAt)
                .one();
        if (exists != null && !Objects.equals(exists.getId(), currentPersonId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手动参与人名称不能重复");
        }
    }

    private Map<Long, UserAccount> linkedUserMap(List<LedgerPerson> people) {
        List<Long> userIds = people.stream()
                .map(LedgerPerson::getLinkedUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery()
                        .in(UserAccount::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private PersonResp toResp(Ledger ledger, LedgerPerson person, UserAccount linkedUser) {
        PersonResp resp = new PersonResp();
        resp.setUuid(person.getUuid());
        resp.setLedgerUuid(ledger.getUuid());
        resp.setLinkedUserUuid(linkedUser == null ? null : linkedUser.getUuid());
        resp.setName(person.getName());
        resp.setAvatar(person.getAvatar());
        resp.setCreatedAt(person.getCreatedAt());
        resp.setUpdatedAt(person.getUpdatedAt());
        return resp;
    }

    private String normalizeAvatar(String avatar) {
        if (!StringUtils.hasText(avatar)) {
            return DEFAULT_AVATAR;
        }
        return avatar.trim();
    }
}

package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.PersonCreateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
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
import java.util.LinkedHashMap;
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
                .map(person -> toResp(ledger, person, linkedUser(person, linkedUserMap)))
                .toList();
    }

    @Override
    public Map<String, List<PersonResp>> batchList(String ledgerUuids) {
        Long userId = StpUtil.getLoginIdAsLong();
        List<String> normalizedUuids = parseLedgerUuids(ledgerUuids);
        if (normalizedUuids.isEmpty()) {
            return Map.of();
        }

        List<Ledger> ledgers = ledgerMapper.selectList(Wrappers.<Ledger>lambdaQuery()
                .in(Ledger::getUuid, normalizedUuids)
                .isNull(Ledger::getDeletedAt));
        Map<String, Ledger> ledgerMap = ledgers.stream()
                .collect(Collectors.toMap(Ledger::getUuid, Function.identity(), (a, b) -> a));
        if (ledgerMap.size() != normalizedUuids.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }

        List<Long> ledgerIds = ledgers.stream().map(Ledger::getId).toList();
        List<LedgerMember> activeMembers = ledgerMemberMapper.selectList(Wrappers.<LedgerMember>lambdaQuery()
                .in(LedgerMember::getLedgerId, ledgerIds)
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt));
        if (activeMembers.size() != normalizedUuids.size()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        List<LedgerPerson> people = lambdaQuery()
                .in(LedgerPerson::getLedgerId, ledgerIds)
                .isNull(LedgerPerson::getDeletedAt)
                .orderByAsc(LedgerPerson::getCreatedAt)
                .list();
        Map<Long, Ledger> ledgerById = ledgers.stream()
                .collect(Collectors.toMap(Ledger::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<LedgerPerson>> peopleByLedgerId = people.stream()
                .collect(Collectors.groupingBy(LedgerPerson::getLedgerId));
        Map<Long, UserAccount> linkedUserMap = linkedUserMap(people);

        Map<String, List<PersonResp>> result = new LinkedHashMap<>();
        for (String ledgerUuid : normalizedUuids) {
            Ledger ledger = ledgerMap.get(ledgerUuid);
            List<PersonResp> ledgerPeople = peopleByLedgerId
                    .getOrDefault(ledger.getId(), List.of())
                    .stream()
                    .map(person -> toResp(ledgerById.get(person.getLedgerId()), person, linkedUser(person, linkedUserMap)))
                    .toList();
            result.put(ledgerUuid, ledgerPeople);
        }
        return result;
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
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        requireManagePeoplePermission(member);
        LedgerPerson person = requirePersonForMutation(ledger.getId(), personUuid);
        if (person.getDeletedAt() != null || !Objects.equals(person.getVersion(), req.getVersion())) {
            throw personConflict(ledger, person, req.getVersion());
        }

        UserAccount linkedUser = findLinkedUser(req.getLinkedUserUuid());
        if (linkedUser != null) {
            ensureLinkedUserNotBound(ledger.getId(), linkedUser.getId(), person.getId());
        } else {
            ensureManualNameNotUsed(ledger.getId(), req.getName(), person.getId());
        }

        Long linkedUserId = linkedUser == null ? null : linkedUser.getId();
        String name = req.getName().trim();
        String avatar = normalizeAvatar(req.getAvatar());
        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerPerson>lambdaUpdate()
                .eq(LedgerPerson::getId, person.getId())
                .eq(LedgerPerson::getVersion, req.getVersion())
                .isNull(LedgerPerson::getDeletedAt)
                .set(LedgerPerson::getLinkedUserId, linkedUserId)
                .set(LedgerPerson::getName, name)
                .set(LedgerPerson::getAvatar, avatar)
                .set(LedgerPerson::getVersion, nextVersion)
                .set(LedgerPerson::getUpdatedAt, updatedAt));
        if (affected == 0) {
            LedgerPerson latest = reloadPersonForUpdate(person.getId(), personUuid);
            throw personConflict(ledger, latest, req.getVersion());
        }
        person.setLinkedUserId(linkedUserId);
        person.setName(name);
        person.setAvatar(avatar);
        person.setVersion(nextVersion);
        person.setUpdatedAt(updatedAt);
        changeLogService.record(ledger.getId(), "person", person.getUuid(), "update", userId);

        return toResp(ledger, person, linkedUser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VersionMutationResp delete(String ledgerUuid, String personUuid, VersionDeleteReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        requireManagePeoplePermission(member);
        LedgerPerson person = requirePersonForMutation(ledger.getId(), personUuid);
        if (person.getDeletedAt() != null || !Objects.equals(person.getVersion(), req.getVersion())) {
            throw personConflict(ledger, person, req.getVersion());
        }

        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerPerson>lambdaUpdate()
                .eq(LedgerPerson::getId, person.getId())
                .eq(LedgerPerson::getVersion, req.getVersion())
                .isNull(LedgerPerson::getDeletedAt)
                .set(LedgerPerson::getDeletedAt, updatedAt)
                .set(LedgerPerson::getUpdatedAt, updatedAt)
                .set(LedgerPerson::getVersion, nextVersion));
        if (affected == 0) {
            LedgerPerson latest = reloadPersonForUpdate(person.getId(), personUuid);
            throw personConflict(ledger, latest, req.getVersion());
        }
        person.setDeletedAt(updatedAt);
        person.setUpdatedAt(updatedAt);
        person.setVersion(nextVersion);
        changeLogService.record(ledger.getId(), "person", person.getUuid(), "delete", userId);
        return new VersionMutationResp(person.getUuid(), nextVersion, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonResp restore(String ledgerUuid, String personUuid, PersonUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMemberForUpdate(ledger.getId(), userId);
        requireManagePeoplePermission(member);
        LedgerPerson person = requirePersonForMutation(ledger.getId(), personUuid);
        if (person.getDeletedAt() == null || !Objects.equals(person.getVersion(), req.getVersion())) {
            throw personConflict(ledger, person, req.getVersion());
        }

        UserAccount linkedUser = findLinkedUser(req.getLinkedUserUuid());
        if (linkedUser != null) {
            ensureLinkedUserNotBound(ledger.getId(), linkedUser.getId(), person.getId());
        } else {
            ensureManualNameNotUsed(ledger.getId(), req.getName(), person.getId());
        }

        Long linkedUserId = linkedUser == null ? null : linkedUser.getId();
        String name = req.getName().trim();
        String avatar = normalizeAvatar(req.getAvatar());
        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerPerson>lambdaUpdate()
                .eq(LedgerPerson::getId, person.getId())
                .eq(LedgerPerson::getVersion, req.getVersion())
                .isNotNull(LedgerPerson::getDeletedAt)
                .set(LedgerPerson::getLinkedUserId, linkedUserId)
                .set(LedgerPerson::getName, name)
                .set(LedgerPerson::getAvatar, avatar)
                .set(LedgerPerson::getDeletedAt, null)
                .set(LedgerPerson::getUpdatedAt, updatedAt)
                .set(LedgerPerson::getVersion, nextVersion));
        if (affected == 0) {
            LedgerPerson latest = reloadPersonForUpdate(person.getId(), personUuid);
            throw personConflict(ledger, latest, req.getVersion());
        }
        person.setLinkedUserId(linkedUserId);
        person.setName(name);
        person.setAvatar(avatar);
        person.setDeletedAt(null);
        person.setUpdatedAt(updatedAt);
        person.setVersion(nextVersion);
        changeLogService.record(ledger.getId(), "person", person.getUuid(), "update", userId);
        return toResp(ledger, person, linkedUser);
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

    private List<String> parseLedgerUuids(String ledgerUuids) {
        if (!StringUtils.hasText(ledgerUuids)) {
            return List.of();
        }
        return List.of(ledgerUuids.split(","))
                .stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
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

    private LedgerPerson requirePersonForMutation(Long ledgerId, String personUuid) {
        LedgerPerson person = baseMapper.selectOne(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .eq(LedgerPerson::getUuid, personUuid));
        if (person == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参与人不存在");
        }
        return person;
    }

    private LedgerPerson reloadPersonForUpdate(Long personId, String personUuid) {
        LedgerPerson latest = baseMapper.selectOne(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getId, personId)
                .last("FOR UPDATE"));
        if (latest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参与人不存在: " + personUuid);
        }
        return latest;
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
        LedgerPerson exists = baseMapper.selectOne(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .eq(LedgerPerson::getLinkedUserId, linkedUserId)
                .isNull(LedgerPerson::getDeletedAt));
        if (exists != null && !Objects.equals(exists.getId(), currentPersonId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该用户已绑定到账本参与人");
        }
    }

    private void ensureManualNameNotUsed(Long ledgerId, String name, Long currentPersonId) {
        String normalizedName = name == null ? "" : name.trim();
        LedgerPerson exists = baseMapper.selectOne(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledgerId)
                .isNull(LedgerPerson::getLinkedUserId)
                .eq(LedgerPerson::getName, normalizedName)
                .isNull(LedgerPerson::getDeletedAt));
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

    private UserAccount linkedUser(LedgerPerson person, Map<Long, UserAccount> linkedUserMap) {
        Long linkedUserId = person.getLinkedUserId();
        if (linkedUserId == null) {
            return null;
        }
        return linkedUserMap.get(linkedUserId);
    }

    private PersonResp toResp(Ledger ledger, LedgerPerson person, UserAccount linkedUser) {
        PersonResp resp = new PersonResp();
        resp.setUuid(person.getUuid());
        resp.setLedgerUuid(ledger.getUuid());
        resp.setLinkedUserUuid(linkedUser == null ? null : linkedUser.getUuid());
        resp.setName(person.getName());
        resp.setAvatar(person.getAvatar());
        resp.setVersion(person.getVersion());
        resp.setCreatedAt(person.getCreatedAt());
        resp.setUpdatedAt(person.getUpdatedAt());
        return resp;
    }

    private VersionConflictException personConflict(Ledger ledger, LedgerPerson person, Integer submittedVersion) {
        UserAccount linkedUser = person.getLinkedUserId() == null
                ? null
                : userAccountMapper.selectById(person.getLinkedUserId());
        PersonResp snapshot = toResp(ledger, person, linkedUser);
        return new VersionConflictException(new ConflictResp(
                "person",
                person.getUuid(),
                submittedVersion,
                person.getVersion(),
                person.getDeletedAt() != null,
                snapshot
        ));
    }

    private String normalizeAvatar(String avatar) {
        if (!StringUtils.hasText(avatar)) {
            return DEFAULT_AVATAR;
        }
        return avatar.trim();
    }
}

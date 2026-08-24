package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.AuthLoginReq;
import com.simon.ledger.dto.req.AuthProfileUpdateReq;
import com.simon.ledger.dto.req.AuthRegisterReq;
import com.simon.ledger.dto.resp.AuthLoginResp;
import com.simon.ledger.dto.resp.AuthUserResp;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.ProfileConflictSnapshotResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.AuthService;
import com.simon.ledger.service.ChangeLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class AuthServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements AuthService {

    private static final int STATUS_NORMAL = 1;
    private static final int STATUS_DISABLED = 2;
    private static final int PROFILE_LEDGER_LOCK_MAX_ATTEMPTS = 3;

    private final LedgerPersonMapper ledgerPersonMapper;
    private final LedgerMapper ledgerMapper;
    private final ChangeLogService changeLogService;
    private final TransactionTemplate profileUpdateTransactions;

    public AuthServiceImpl(
            LedgerPersonMapper ledgerPersonMapper,
            LedgerMapper ledgerMapper,
            ChangeLogService changeLogService,
            PlatformTransactionManager transactionManager
    ) {
        this.ledgerPersonMapper = ledgerPersonMapper;
        this.ledgerMapper = ledgerMapper;
        this.changeLogService = changeLogService;
        this.profileUpdateTransactions = new TransactionTemplate(transactionManager);
        this.profileUpdateTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AuthUserResp register(AuthRegisterReq req) {
        String email = normalize(req.getEmail());
        String phone = normalize(req.getPhone());
        if (!StringUtils.hasText(email) && !StringUtils.hasText(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱和手机号至少填写一个");
        }

        if (StringUtils.hasText(email) && existsByEmail(email)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已注册");
        }
        if (StringUtils.hasText(phone) && existsByPhone(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号已注册");
        }

        UserAccount user = new UserAccount();
        user.setUuid(IdUtil.fastSimpleUUID());
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(BCrypt.hashpw(req.getPassword()));
        user.setNickname(req.getNickname().trim());
        user.setAvatar(normalize(req.getAvatar()));
        user.setStatus(STATUS_NORMAL);
        save(user);
        return toUserResp(user);
    }

    @Override
    public AuthLoginResp login(AuthLoginReq req) {
        UserAccount user = findByAccount(req.getAccount());
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号或密码错误");
        }
        if (STATUS_DISABLED == user.getStatus()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        if (!StringUtils.hasText(user.getPasswordHash()) || !BCrypt.checkpw(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号或密码错误");
        }

        StpUtil.login(user.getId());

        AuthLoginResp resp = new AuthLoginResp();
        resp.setTokenName(StpUtil.getTokenName());
        resp.setTokenValue(StpUtil.getTokenValue());
        resp.setUser(toUserResp(user));
        return resp;
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public AuthUserResp me() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserAccount user = getById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已删除");
        }
        if (STATUS_DISABLED == user.getStatus()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return toUserResp(user);
    }

    @Override
    public AuthUserResp updateProfile(AuthProfileUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        for (int attempt = 1; attempt <= PROFILE_LEDGER_LOCK_MAX_ATTEMPTS; attempt++) {
            try {
                AuthUserResp response = profileUpdateTransactions.execute(
                        status -> updateProfileOnce(userId, req));
                if (response == null) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR);
                }
                return response;
            } catch (ProfileLedgerLockDriftException exception) {
                if (attempt == PROFILE_LEDGER_LOCK_MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.CONFLICT, "关联账本状态变化，请重试");
                }
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "关联账本状态变化，请重试");
    }

    private AuthUserResp updateProfileOnce(Long userId, AuthProfileUpdateReq req) {
        List<Long> linkedLedgerIds = discoverActiveLinkedLedgerIds(userId);
        lockLedgerNamespaces(linkedLedgerIds);
        UserAccount user = requireCurrentProfileForUpdate(userId);
        if (STATUS_DISABLED == user.getStatus()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }

        if (!Objects.equals(req.getVersion(), user.getVersion())) {
            throw profileConflict(req.getVersion(), user);
        }

        List<LedgerPerson> linkedPeople = lockCurrentLinkedPeople(userId);
        requireStableLedgerNamespaces(linkedLedgerIds, linkedPeople);

        String nickname = req.getNickname().trim();
        String avatar = normalize(req.getAvatar());
        int nextVersion = req.getVersion() + 1;
        LocalDateTime updatedAt = LocalDateTime.now();
        int affected = baseMapper.update(null, Wrappers.<UserAccount>lambdaUpdate()
                .eq(UserAccount::getId, userId)
                .eq(UserAccount::getVersion, req.getVersion())
                .isNull(UserAccount::getDeletedAt)
                .set(UserAccount::getNickname, nickname)
                .set(UserAccount::getAvatar, avatar)
                .set(UserAccount::getVersion, nextVersion)
                .set(UserAccount::getUpdatedAt, updatedAt));
        if (affected == 0) {
            UserAccount current = requireCurrentProfileForUpdate(userId);
            throw profileConflict(req.getVersion(), current);
        }

        user.setNickname(nickname);
        user.setAvatar(avatar);
        user.setVersion(nextVersion);
        user.setUpdatedAt(updatedAt);
        syncLockedPeople(userId, user, linkedPeople);
        return toUserResp(user);
    }

    private List<Long> discoverActiveLinkedLedgerIds(Long userId) {
        return ledgerPersonMapper.selectObjs(Wrappers.<LedgerPerson>query()
                        .select("DISTINCT ledger_id")
                        .eq("linked_user_id", userId)
                        .isNull("deleted_at")
                        .orderByAsc("ledger_id"))
                .stream()
                .filter(Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .sorted()
                .toList();
    }

    private void lockLedgerNamespaces(List<Long> ledgerIds) {
        if (ledgerIds.isEmpty()) {
            return;
        }
        ledgerMapper.selectList(Wrappers.<Ledger>lambdaQuery()
                .in(Ledger::getId, ledgerIds)
                .orderByAsc(Ledger::getId)
                .last("FOR UPDATE"));
    }

    private UserAccount requireCurrentProfileForUpdate(Long userId) {
        UserAccount user = baseMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getId, userId)
                .isNull(UserAccount::getDeletedAt)
                .last("FOR UPDATE"));
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private List<LedgerPerson> lockCurrentLinkedPeople(Long userId) {
        return ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLinkedUserId, userId)
                .isNull(LedgerPerson::getDeletedAt)
                .orderByAsc(LedgerPerson::getId)
                .last("FOR UPDATE"));
    }

    private void requireStableLedgerNamespaces(List<Long> lockedLedgerIds, List<LedgerPerson> people) {
        Set<Long> locked = new HashSet<>(lockedLedgerIds);
        boolean drifted = people.stream()
                .map(LedgerPerson::getLedgerId)
                .filter(Objects::nonNull)
                .anyMatch(ledgerId -> !locked.contains(ledgerId));
        if (drifted) {
            throw new ProfileLedgerLockDriftException();
        }
    }

    private void syncLockedPeople(Long userId, UserAccount user, List<LedgerPerson> people) {
        LocalDateTime peopleUpdatedAt = LocalDateTime.now();
        for (LedgerPerson person : people) {
            int affected = ledgerPersonMapper.update(null, Wrappers.<LedgerPerson>lambdaUpdate()
                    .eq(LedgerPerson::getId, person.getId())
                    .eq(LedgerPerson::getLinkedUserId, userId)
                    .eq(LedgerPerson::getVersion, person.getVersion())
                    .isNull(LedgerPerson::getDeletedAt)
                    .set(LedgerPerson::getName, user.getNickname())
                    .set(LedgerPerson::getAvatar, user.getAvatar() == null ? "" : user.getAvatar())
                    .set(LedgerPerson::getUpdatedAt, peopleUpdatedAt)
                    .setSql("version = version + 1"));
            if (affected == 1) {
                changeLogService.record(person.getLedgerId(), "person", person.getUuid(), "update", userId);
            }
        }
    }

    private static final class ProfileLedgerLockDriftException extends RuntimeException {
    }

    private VersionConflictException profileConflict(Integer submittedVersion, UserAccount remote) {
        ProfileConflictSnapshotResp snapshot = new ProfileConflictSnapshotResp(
                remote.getUuid(), remote.getNickname(), remote.getAvatar(), remote.getVersion());
        return new VersionConflictException(new ConflictResp(
                "profile", remote.getUuid(), submittedVersion, remote.getVersion(), false, snapshot));
    }

    private boolean existsByEmail(String email) {
        return lambdaQuery()
                .eq(UserAccount::getEmail, email)
                .isNull(UserAccount::getDeletedAt)
                .exists();
    }

    private boolean existsByPhone(String phone) {
        return lambdaQuery()
                .eq(UserAccount::getPhone, phone)
                .isNull(UserAccount::getDeletedAt)
                .exists();
    }

    private UserAccount findByAccount(String account) {
        String normalizedAccount = normalize(account);
        if (!StringUtils.hasText(normalizedAccount)) {
            return null;
        }
        return lambdaQuery()
                .and(q -> q.eq(UserAccount::getEmail, normalizedAccount)
                        .or()
                        .eq(UserAccount::getPhone, normalizedAccount))
                .isNull(UserAccount::getDeletedAt)
                .one();
    }

    private AuthUserResp toUserResp(UserAccount user) {
        AuthUserResp resp = new AuthUserResp();
        resp.setUuid(user.getUuid());
        resp.setEmail(user.getEmail());
        resp.setPhone(user.getPhone());
        resp.setNickname(user.getNickname());
        resp.setAvatar(user.getAvatar());
        resp.setStatus(user.getStatus());
        resp.setVersion(user.getVersion());
        return resp;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}

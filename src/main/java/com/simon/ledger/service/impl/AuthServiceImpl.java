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
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.AuthService;
import com.simon.ledger.service.ChangeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements AuthService {

    private static final int STATUS_NORMAL = 1;
    private static final int STATUS_DISABLED = 2;

    private final LedgerPersonMapper ledgerPersonMapper;
    private final ChangeLogService changeLogService;

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
    @Transactional(rollbackFor = Exception.class)
    public AuthUserResp updateProfile(AuthProfileUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        UserAccount user = getById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (STATUS_DISABLED == user.getStatus()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }

        if (!Objects.equals(req.getVersion(), user.getVersion())) {
            throw profileConflict(req.getVersion(), user);
        }

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
            UserAccount current = baseMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                    .eq(UserAccount::getId, userId)
                    .isNull(UserAccount::getDeletedAt)
                    .last("FOR UPDATE"));
            if (current == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            throw profileConflict(req.getVersion(), current);
        }

        user.setNickname(nickname);
        user.setAvatar(avatar);
        user.setVersion(nextVersion);
        user.setUpdatedAt(updatedAt);
        syncLinkedPeople(userId, user);
        return toUserResp(user);
    }

    private void syncLinkedPeople(Long userId, UserAccount user) {
        List<LedgerPerson> people = ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLinkedUserId, userId)
                .isNull(LedgerPerson::getDeletedAt));
        for (LedgerPerson person : people) {
            int affected = ledgerPersonMapper.update(null, Wrappers.<LedgerPerson>lambdaUpdate()
                    .eq(LedgerPerson::getId, person.getId())
                    .isNull(LedgerPerson::getDeletedAt)
                    .set(LedgerPerson::getName, user.getNickname())
                    .set(LedgerPerson::getAvatar, user.getAvatar() == null ? "" : user.getAvatar())
                    .set(LedgerPerson::getUpdatedAt, LocalDateTime.now())
                    .setSql("version = version + 1"));
            if (affected == 1) {
                changeLogService.record(person.getLedgerId(), "person", person.getUuid(), "update", userId);
            }
        }
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

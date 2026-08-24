package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.InviteCreateReq;
import com.simon.ledger.dto.req.InviteRegenerateReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.InviteMemberSummaryResp;
import com.simon.ledger.dto.resp.InviteResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerInvite;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerInviteMapper;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InviteServiceImpl extends ServiceImpl<LedgerInviteMapper, LedgerInvite> implements InviteService {

    private static final int MEMBER_STATUS_ACTIVE = 1;
    private static final List<Integer> ALLOWED_REGENERATE_DAYS = List.of(1, 3, 5, 7);

    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final ChangeLogService changeLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InviteResp create(String ledgerUuid, InviteCreateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMemberForUpdate(ledger.getId(), userId);
        String role = normalizeRole(req.getRole());
        if (!LedgerRoles.isValidJoinableRole(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请角色不正确");
        }
        requireInvitePermission(operator, role);
        LocalDateTime now = LocalDateTime.now();
        disableUsableInvites(ledger.getId(), now);
        LedgerInvite invite = createInvite(ledger, userId, role, req.getMaxUses(), req.getExpiresAt(), now);
        return toResp(invite, ledger);
    }

    @Override
    public InviteResp current(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        requireLedgerManager(ledger.getId(), userId);
        LedgerInvite invite = currentUsableInvite(ledger.getId(), LocalDateTime.now());
        return invite == null ? null : toResp(invite, ledger);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InviteResp regenerate(String ledgerUuid, InviteRegenerateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMemberForUpdate(ledger.getId(), userId);
        int days = requireAllowedDays(req.getDays());
        String role = normalizeRole(req.getRole());
        if (!LedgerRoles.isValidJoinableRole(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请角色不正确");
        }
        requireInvitePermission(operator, role);
        LocalDateTime now = LocalDateTime.now();
        disableUsableInvites(ledger.getId(), now);
        LedgerInvite invite = createInvite(ledger, userId, role, req.getMaxUses(), now.plusDays(days), now);
        return toResp(invite, ledger);
    }

    private LedgerInvite createInvite(
            Ledger ledger,
            Long userId,
            String role,
            Integer maxUses,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        LedgerInvite invite = new LedgerInvite();
        invite.setUuid(IdUtil.fastSimpleUUID());
        invite.setLedgerId(ledger.getId());
        invite.setCode(generateCode());
        invite.setRole(role);
        invite.setCreatedByUserId(userId);
        invite.setMaxUses(maxUses);
        invite.setUsedCount(0);
        invite.setExpiresAt(expiresAt);
        invite.setCreatedAt(now);
        save(invite);
        return invite;
    }

    @Override
    public InviteResp getByCode(String code) {
        LedgerInvite invite = requireInvite(code);
        Ledger ledger = ledgerMapper.selectById(invite.getLedgerId());
        if (ledger == null || ledger.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }
        return toResp(invite, ledger);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InviteResp join(String code) {
        Long userId = StpUtil.getLoginIdAsLong();
        LedgerInvite invite = requireUsableInvite(code);
        Ledger ledger = ledgerMapper.selectById(invite.getLedgerId());
        if (ledger == null || ledger.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }
        String invitedRole = normalizeRole(invite.getRole());
        if (!LedgerRoles.isValidJoinableRole(invitedRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请角色不正确");
        }
        if (LedgerRoles.ADMIN.equals(invitedRole)) {
            requireAdminInviteCreatedByCurrentOwner(ledger.getId(), invite.getCreatedByUserId());
        }

        LedgerMember exists = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getUserId, userId));
        if (exists != null && exists.getDeletedAt() == null
                && Integer.valueOf(MEMBER_STATUS_ACTIVE).equals(exists.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已加入该账本");
        }
        if (exists == null) {
            LedgerMember member = new LedgerMember();
            member.setUuid(IdUtil.fastSimpleUUID());
            member.setLedgerId(ledger.getId());
            member.setUserId(userId);
            member.setRole(invitedRole);
            member.setStatus(MEMBER_STATUS_ACTIVE);
            member.setJoinedAt(LocalDateTime.now());
            ledgerMemberMapper.insert(member);
            changeLogService.record(ledger.getId(), "member", member.getUuid(), "create", userId);
        } else {
            restoreExistingMember(ledger, exists, invitedRole, userId);
        }

        incrementInviteUsage(invite);
        return toResp(invite, ledger);
    }

    private void restoreExistingMember(Ledger ledger, LedgerMember observed, String invitedRole, Long userId) {
        Integer observedVersion = observed.getVersion();
        int nextVersion = observedVersion + 1;
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<LedgerMember> wrapper = Wrappers.<LedgerMember>lambdaUpdate()
                .eq(LedgerMember::getId, observed.getId())
                .eq(LedgerMember::getVersion, observedVersion)
                .and(condition -> condition
                        .ne(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                        .or()
                        .isNotNull(LedgerMember::getDeletedAt))
                .set(LedgerMember::getRole, invitedRole)
                .set(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .set(LedgerMember::getJoinedAt, now)
                .set(LedgerMember::getDeletedAt, null)
                .set(LedgerMember::getUpdatedAt, now)
                .set(LedgerMember::getVersion, nextVersion);
        if (ledgerMemberMapper.update(null, wrapper) != 1) {
            LedgerMember latest = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                    .eq(LedgerMember::getId, observed.getId())
                    .last("FOR UPDATE"));
            throw memberConflict(observed, latest, observedVersion);
        }

        observed.setRole(invitedRole);
        observed.setStatus(MEMBER_STATUS_ACTIVE);
        observed.setJoinedAt(now);
        observed.setDeletedAt(null);
        observed.setUpdatedAt(now);
        observed.setVersion(nextVersion);
        changeLogService.record(ledger.getId(), "member", observed.getUuid(), "update", userId);
    }

    private VersionConflictException memberConflict(
            LedgerMember observed,
            LedgerMember latest,
            Integer submittedVersion
    ) {
        if (latest == null) {
            return new VersionConflictException(new ConflictResp(
                    "member",
                    observed.getUuid(),
                    submittedVersion,
                    null,
                    true,
                    null
            ));
        }
        UserAccount user = userAccountMapper.selectById(latest.getUserId());
        return new VersionConflictException(new ConflictResp(
                "member",
                latest.getUuid(),
                submittedVersion,
                latest.getVersion(),
                latest.getDeletedAt() != null,
                toMemberResp(latest, user)
        ));
    }

    private MemberResp toMemberResp(LedgerMember member, UserAccount user) {
        MemberResp resp = new MemberResp();
        resp.setUuid(member.getUuid());
        resp.setUserUuid(user == null ? null : user.getUuid());
        resp.setNickname(user == null ? null : user.getNickname());
        resp.setAvatar(user == null ? null : user.getAvatar());
        resp.setRole(member.getRole());
        resp.setStatus(member.getStatus());
        resp.setJoinedAt(member.getJoinedAt());
        resp.setVersion(member.getVersion());
        return resp;
    }

    private void incrementInviteUsage(LedgerInvite invite) {
        int oldUsedCount = invite.getUsedCount();
        LambdaUpdateWrapper<LedgerInvite> wrapper = Wrappers.<LedgerInvite>lambdaUpdate()
                .eq(LedgerInvite::getId, invite.getId())
                .isNull(LedgerInvite::getDisabledAt)
                .gt(LedgerInvite::getExpiresAt, LocalDateTime.now())
                .apply("(max_uses IS NULL OR used_count < max_uses)")
                .setSql("used_count = used_count + 1");
        if (baseMapper.update(null, wrapper) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "邀请码状态已变化，请重试");
        }
        invite.setUsedCount(oldUsedCount + 1);
    }

    private LedgerInvite currentUsableInvite(Long ledgerId, LocalDateTime now) {
        return getOne(Wrappers.<LedgerInvite>lambdaQuery()
                .eq(LedgerInvite::getLedgerId, ledgerId)
                .isNull(LedgerInvite::getDisabledAt)
                .gt(LedgerInvite::getExpiresAt, now)
                .apply("(max_uses IS NULL OR used_count < max_uses)")
                .orderByDesc(LedgerInvite::getCreatedAt)
                .orderByDesc(LedgerInvite::getId)
                .last("limit 1"));
    }

    private void disableUsableInvites(Long ledgerId, LocalDateTime now) {
        LambdaUpdateWrapper<LedgerInvite> wrapper = Wrappers.<LedgerInvite>lambdaUpdate()
                .eq(LedgerInvite::getLedgerId, ledgerId)
                .isNull(LedgerInvite::getDisabledAt)
                .gt(LedgerInvite::getExpiresAt, now)
                .apply("(max_uses IS NULL OR used_count < max_uses)")
                .set(LedgerInvite::getDisabledAt, now);
        baseMapper.update(null, wrapper);
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

    private void requireInvitePermission(LedgerMember operator, String invitedRole) {
        if (!LedgerRoles.canCreateInvite(operator.getRole(), invitedRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireAdminInviteCreatedByCurrentOwner(Long ledgerId, Long createdByUserId) {
        if (createdByUserId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LedgerMember creator = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUserId, createdByUserId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .last("FOR UPDATE"));
        if (creator == null || !LedgerRoles.OWNER.equals(creator.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireLedgerManager(Long ledgerId, Long userId) {
        LedgerMember member = requireActiveMember(ledgerId, userId);
        if (!LedgerRoles.canManageLedger(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private LedgerInvite requireInvite(String code) {
        LedgerInvite invite = baseMapper.selectOne(
                Wrappers.<LedgerInvite>lambdaQuery().eq(LedgerInvite::getCode, code));
        if (invite == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邀请码不存在");
        }
        return invite;
    }

    private LedgerInvite requireUsableInvite(String code) {
        LedgerInvite invite = requireInvite(code);
        if (invite.getDisabledAt() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请码已禁用");
        }
        if (!invite.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请码已过期");
        }
        if (invite.getMaxUses() != null && invite.getUsedCount() >= invite.getMaxUses()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请码使用次数已达上限");
        }
        return invite;
    }

    private InviteResp toResp(LedgerInvite invite, Ledger ledger) {
        List<LedgerMember> members = ledgerMemberMapper.selectList(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .orderByAsc(LedgerMember::getJoinedAt));
        Map<Long, UserAccount> userMap = userMap(members);

        InviteResp resp = new InviteResp();
        resp.setUuid(invite.getUuid());
        resp.setCode(invite.getCode());
        resp.setLedgerUuid(ledger.getUuid());
        resp.setLedgerName(ledger.getName());
        resp.setLedgerBaseCurrencyCode(ledger.getBaseCurrencyCode());
        resp.setLedgerMemberCount(members.size());
        resp.setLedgerMembers(members.stream()
                .map(member -> toMemberSummary(member, userMap.get(member.getUserId())))
                .toList());
        resp.setRole(invite.getRole());
        resp.setMaxUses(invite.getMaxUses());
        resp.setUsedCount(invite.getUsedCount());
        resp.setExpiresAt(invite.getExpiresAt());
        resp.setCreatedAt(invite.getCreatedAt());
        resp.setExpired(!invite.getExpiresAt().isAfter(LocalDateTime.now()));
        resp.setDisabled(invite.getDisabledAt() != null);
        return resp;
    }

    private Map<Long, UserAccount> userMap(List<LedgerMember> members) {
        List<Long> userIds = members.stream()
                .map(LedgerMember::getUserId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private InviteMemberSummaryResp toMemberSummary(LedgerMember member, UserAccount user) {
        InviteMemberSummaryResp resp = new InviteMemberSummaryResp();
        resp.setNickname(user == null ? null : user.getNickname());
        resp.setAvatar(user == null ? null : user.getAvatar());
        resp.setRole(member.getRole());
        return resp;
    }

    private String generateCode() {
        String code;
        do {
            code = RandomUtil.randomStringUpper(8);
        } while (baseMapper.selectCount(
                Wrappers.<LedgerInvite>lambdaQuery().eq(LedgerInvite::getCode, code)) > 0);
        return code;
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }

    private int requireAllowedDays(Integer days) {
        if (days == null || !ALLOWED_REGENERATE_DAYS.contains(days)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请有效期不正确");
        }
        return days;
    }
}

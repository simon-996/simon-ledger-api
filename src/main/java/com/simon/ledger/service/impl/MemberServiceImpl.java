package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.MemberService;
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
public class MemberServiceImpl extends ServiceImpl<LedgerMemberMapper, LedgerMember> implements MemberService {

    private static final int MEMBER_STATUS_ACTIVE = 1;

    private final LedgerMapper ledgerMapper;
    private final UserAccountMapper userAccountMapper;
    private final ChangeLogService changeLogService;

    @Override
    public List<MemberResp> list(String ledgerUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        requireActiveMember(ledger.getId(), userId);
        List<LedgerMember> members = baseMapper.selectList(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .orderByAsc(LedgerMember::getJoinedAt));
        Map<Long, UserAccount> userMap = userMap(members);
        return members.stream().map(member -> toResp(member, userMap.get(member.getUserId()))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemberResp updateRole(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMemberForUpdate(ledger.getId(), userId);
        requireManageMemberPermission(operator);
        LedgerMember target = requireMemberForMutation(ledger.getId(), memberUuid);
        String newRole = normalizeRole(req.getRole());
        if (!LedgerRoles.isValidJoinableRole(newRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不正确");
        }
        requireAssignRolePermission(operator.getRole(), target.getRole(), newRole);
        if (target.getDeletedAt() != null
                || !Integer.valueOf(MEMBER_STATUS_ACTIVE).equals(target.getStatus())
                || !target.getVersion().equals(req.getVersion())) {
            throw memberConflict(target, req.getVersion());
        }

        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerMember>lambdaUpdate()
                .eq(LedgerMember::getId, target.getId())
                .eq(LedgerMember::getVersion, req.getVersion())
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .set(LedgerMember::getRole, newRole)
                .set(LedgerMember::getVersion, nextVersion)
                .set(LedgerMember::getUpdatedAt, updatedAt));
        if (affected == 0) {
            LedgerMember latest = reloadMemberForUpdate(target.getId(), memberUuid);
            requireAssignRolePermission(operator.getRole(), latest.getRole(), newRole);
            throw memberConflict(latest, req.getVersion());
        }
        target.setRole(newRole);
        target.setVersion(nextVersion);
        target.setUpdatedAt(updatedAt);
        changeLogService.record(ledger.getId(), "member", target.getUuid(), "update", userId);
        return toResp(target, userAccountMapper.selectById(target.getUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VersionMutationResp remove(String ledgerUuid, String memberUuid, VersionDeleteReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMemberForUpdate(ledger.getId(), userId);
        requireManageMemberPermission(operator);
        LedgerMember target = requireMemberForMutation(ledger.getId(), memberUuid);
        requireRemoveRolePermission(operator.getRole(), target.getRole());
        if (target.getDeletedAt() != null
                || !Integer.valueOf(MEMBER_STATUS_ACTIVE).equals(target.getStatus())
                || !target.getVersion().equals(req.getVersion())) {
            throw memberConflict(target, req.getVersion());
        }

        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerMember>lambdaUpdate()
                .eq(LedgerMember::getId, target.getId())
                .eq(LedgerMember::getVersion, req.getVersion())
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .set(LedgerMember::getDeletedAt, updatedAt)
                .set(LedgerMember::getUpdatedAt, updatedAt)
                .set(LedgerMember::getVersion, nextVersion));
        if (affected == 0) {
            LedgerMember latest = reloadMemberForUpdate(target.getId(), memberUuid);
            requireRemoveRolePermission(operator.getRole(), latest.getRole());
            throw memberConflict(latest, req.getVersion());
        }
        target.setDeletedAt(updatedAt);
        target.setUpdatedAt(updatedAt);
        target.setVersion(nextVersion);
        changeLogService.record(ledger.getId(), "member", target.getUuid(), "delete", userId);
        return new VersionMutationResp(target.getUuid(), nextVersion, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemberResp restore(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMemberForUpdate(ledger.getId(), userId);
        requireManageMemberPermission(operator);
        LedgerMember target = requireMemberForMutation(ledger.getId(), memberUuid);
        String newRole = normalizeRole(req.getRole());
        if (!LedgerRoles.isValidJoinableRole(newRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不正确");
        }
        requireAssignRolePermission(operator.getRole(), target.getRole(), newRole);
        if (target.getDeletedAt() == null
                || !Integer.valueOf(MEMBER_STATUS_ACTIVE).equals(target.getStatus())
                || !target.getVersion().equals(req.getVersion())) {
            throw memberConflict(target, req.getVersion());
        }

        LocalDateTime updatedAt = LocalDateTime.now();
        int nextVersion = req.getVersion() + 1;
        int affected = baseMapper.update(null, Wrappers.<LedgerMember>lambdaUpdate()
                .eq(LedgerMember::getId, target.getId())
                .eq(LedgerMember::getVersion, req.getVersion())
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNotNull(LedgerMember::getDeletedAt)
                .set(LedgerMember::getRole, newRole)
                .set(LedgerMember::getDeletedAt, null)
                .set(LedgerMember::getUpdatedAt, updatedAt)
                .set(LedgerMember::getVersion, nextVersion));
        if (affected == 0) {
            LedgerMember latest = reloadMemberForUpdate(target.getId(), memberUuid);
            requireAssignRolePermission(operator.getRole(), latest.getRole(), newRole);
            throw memberConflict(latest, req.getVersion());
        }
        target.setRole(newRole);
        target.setDeletedAt(null);
        target.setUpdatedAt(updatedAt);
        target.setVersion(nextVersion);
        changeLogService.record(ledger.getId(), "member", target.getUuid(), "update", userId);
        return toResp(target, userAccountMapper.selectById(target.getUserId()));
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
        LedgerMember member = baseMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt));
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    private LedgerMember requireMemberForMutation(Long ledgerId, String memberUuid) {
        LedgerMember member = baseMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUuid, memberUuid));
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "成员不存在");
        }
        return member;
    }

    private LedgerMember requireActiveMemberForUpdate(Long ledgerId, Long userId) {
        LedgerMember member = baseMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
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

    private LedgerMember reloadMemberForUpdate(Long memberId, String memberUuid) {
        LedgerMember latest = baseMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getId, memberId)
                .last("FOR UPDATE"));
        if (latest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "成员不存在: " + memberUuid);
        }
        return latest;
    }

    private void requireManageMemberPermission(LedgerMember member) {
        if (!LedgerRoles.canManageLedger(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireAssignRolePermission(String operatorRole, String targetRole, String newRole) {
        if (LedgerRoles.canAssignRole(operatorRole, targetRole, newRole)) {
            return;
        }
        if (LedgerRoles.isOwner(targetRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能修改 owner 角色");
        }
        if (LedgerRoles.ADMIN.equals(targetRole) || LedgerRoles.ADMIN.equals(newRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有 owner 可以管理 admin");
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void requireRemoveRolePermission(String operatorRole, String targetRole) {
        if (LedgerRoles.canRemoveRole(operatorRole, targetRole)) {
            return;
        }
        if (LedgerRoles.isOwner(targetRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能移除 owner");
        }
        if (LedgerRoles.ADMIN.equals(targetRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有 owner 可以移除 admin");
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private Map<Long, UserAccount> userMap(List<LedgerMember> members) {
        List<Long> userIds = members.stream().map(LedgerMember::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
    }

    private MemberResp toResp(LedgerMember member, UserAccount user) {
        MemberResp resp = new MemberResp();
        resp.setUuid(member.getUuid());
        resp.setUserUuid(user == null ? null : user.getUuid());
        resp.setNickname(user == null ? null : user.getNickname());
        resp.setAvatar(user == null ? null : user.getAvatar());
        resp.setRole(member.getRole());
        resp.setStatus(member.getStatus());
        resp.setVersion(member.getVersion());
        resp.setJoinedAt(member.getJoinedAt());
        return resp;
    }

    private VersionConflictException memberConflict(LedgerMember member, Integer submittedVersion) {
        MemberResp snapshot = toResp(member, userAccountMapper.selectById(member.getUserId()));
        return new VersionConflictException(new ConflictResp(
                "member",
                member.getUuid(),
                submittedVersion,
                member.getVersion(),
                member.getDeletedAt() != null,
                snapshot
        ));
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }
}

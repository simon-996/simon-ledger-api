package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.resp.MemberResp;
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
        List<LedgerMember> members = lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .isNull(LedgerMember::getDeletedAt)
                .orderByAsc(LedgerMember::getJoinedAt)
                .list();
        Map<Long, UserAccount> userMap = userMap(members);
        return members.stream().map(member -> toResp(member, userMap.get(member.getUserId()))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemberResp updateRole(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMember(ledger.getId(), userId);
        requireManageMemberPermission(operator);
        LedgerMember target = requireMember(ledger.getId(), memberUuid);
        String newRole = normalizeRole(req.getRole());
        if (!LedgerRoles.isValidJoinableRole(newRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不正确");
        }
        if (LedgerRoles.isOwner(target.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能修改 owner 角色");
        }
        if (LedgerRoles.ADMIN.equals(target.getRole()) && !LedgerRoles.isOwner(operator.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有 owner 可以修改 admin");
        }
        target.setRole(newRole);
        updateById(target);
        changeLogService.record(ledger.getId(), "member", target.getUuid(), "update", userId);
        return toResp(target, userAccountMapper.selectById(target.getUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(String ledgerUuid, String memberUuid) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember operator = requireActiveMember(ledger.getId(), userId);
        requireManageMemberPermission(operator);
        LedgerMember target = requireMember(ledger.getId(), memberUuid);
        if (LedgerRoles.isOwner(target.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能移除 owner");
        }
        if (LedgerRoles.ADMIN.equals(target.getRole()) && !LedgerRoles.isOwner(operator.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有 owner 可以移除 admin");
        }
        target.setDeletedAt(LocalDateTime.now());
        updateById(target);
        changeLogService.record(ledger.getId(), "member", target.getUuid(), "delete", userId);
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
        LedgerMember member = lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, MEMBER_STATUS_ACTIVE)
                .isNull(LedgerMember::getDeletedAt)
                .one();
        if (member == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    private LedgerMember requireMember(Long ledgerId, String memberUuid) {
        LedgerMember member = lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledgerId)
                .eq(LedgerMember::getUuid, memberUuid)
                .isNull(LedgerMember::getDeletedAt)
                .one();
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "成员不存在");
        }
        return member;
    }

    private void requireManageMemberPermission(LedgerMember member) {
        if (!LedgerRoles.canManageLedger(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
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
        resp.setJoinedAt(member.getJoinedAt());
        return resp;
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }
}

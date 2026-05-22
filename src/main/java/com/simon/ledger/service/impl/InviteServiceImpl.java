package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.InviteCreateReq;
import com.simon.ledger.dto.resp.InviteResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerInvite;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.mapper.LedgerInviteMapper;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InviteServiceImpl extends ServiceImpl<LedgerInviteMapper, LedgerInvite> implements InviteService {

    private static final int MEMBER_STATUS_ACTIVE = 1;

    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final ChangeLogService changeLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InviteResp create(String ledgerUuid, InviteCreateReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        Ledger ledger = requireLedger(ledgerUuid);
        LedgerMember member = requireActiveMember(ledger.getId(), userId);
        if (!LedgerRoles.canManageLedger(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        String role = normalizeRole(req.getRole());
        if (!LedgerRoles.isValidJoinableRole(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请角色不正确");
        }

        LedgerInvite invite = new LedgerInvite();
        invite.setUuid(IdUtil.fastSimpleUUID());
        invite.setLedgerId(ledger.getId());
        invite.setCode(generateCode());
        invite.setRole(role);
        invite.setCreatedByUserId(userId);
        invite.setMaxUses(req.getMaxUses());
        invite.setUsedCount(0);
        invite.setExpiresAt(req.getExpiresAt());
        invite.setCreatedAt(LocalDateTime.now());
        save(invite);
        return toResp(invite, ledger);
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

        LedgerMember exists = ledgerMemberMapper.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getUserId, userId));
        if (exists != null && exists.getDeletedAt() == null && MEMBER_STATUS_ACTIVE == exists.getStatus()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已加入该账本");
        }
        if (exists == null) {
            LedgerMember member = new LedgerMember();
            member.setUuid(IdUtil.fastSimpleUUID());
            member.setLedgerId(ledger.getId());
            member.setUserId(userId);
            member.setRole(invite.getRole());
            member.setStatus(MEMBER_STATUS_ACTIVE);
            member.setJoinedAt(LocalDateTime.now());
            ledgerMemberMapper.insert(member);
            changeLogService.record(ledger.getId(), "member", member.getUuid(), "create", userId);
        } else {
            exists.setRole(invite.getRole());
            exists.setStatus(MEMBER_STATUS_ACTIVE);
            exists.setJoinedAt(LocalDateTime.now());
            exists.setDeletedAt(null);
            ledgerMemberMapper.updateById(exists);
            changeLogService.record(ledger.getId(), "member", exists.getUuid(), "update", userId);
        }

        invite.setUsedCount(invite.getUsedCount() + 1);
        updateById(invite);
        return toResp(invite, ledger);
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

    private LedgerInvite requireInvite(String code) {
        LedgerInvite invite = getOne(Wrappers.<LedgerInvite>lambdaQuery().eq(LedgerInvite::getCode, code));
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
        InviteResp resp = new InviteResp();
        resp.setUuid(invite.getUuid());
        resp.setCode(invite.getCode());
        resp.setLedgerUuid(ledger.getUuid());
        resp.setLedgerName(ledger.getName());
        resp.setRole(invite.getRole());
        resp.setMaxUses(invite.getMaxUses());
        resp.setUsedCount(invite.getUsedCount());
        resp.setExpiresAt(invite.getExpiresAt());
        resp.setCreatedAt(invite.getCreatedAt());
        resp.setExpired(!invite.getExpiresAt().isAfter(LocalDateTime.now()));
        resp.setDisabled(invite.getDisabledAt() != null);
        return resp;
    }

    private String generateCode() {
        String code;
        do {
            code = RandomUtil.randomStringUpper(8);
        } while (lambdaQuery().eq(LedgerInvite::getCode, code).exists());
        return code;
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }
}

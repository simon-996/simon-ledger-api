package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiBookkeepingAccess {
    private final UserAccountMapper users;
    private final LedgerMapper ledgers;
    private final LedgerMemberMapper members;

    public Context requireAllowed(String ledgerUuid) {
        final long userId;
        try {
            userId = Long.parseLong(StpUtil.getLoginId().toString());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "普通账号登录态无效");
        }
        UserAccount user = users.selectById(userId);
        if (user == null || user.getDeletedAt() != null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在或已禁用");
        }
        if (!Boolean.TRUE.equals(user.getAiBookkeepingEnabled())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "未开通 AI 记账");
        }
        Ledger ledger = ledgers.selectOne(Wrappers.<Ledger>lambdaQuery()
                .eq(Ledger::getUuid, ledgerUuid)
                .isNull(Ledger::getDeletedAt));
        if (ledger == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账本不存在");
        }
        LedgerMember member = members.selectOne(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .eq(LedgerMember::getUserId, userId)
                .eq(LedgerMember::getStatus, 1)
                .isNull(LedgerMember::getDeletedAt));
        if (member == null || !LedgerRoles.canCreateTransaction(member.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有账本记账权限");
        }
        return new Context(user, ledger, member);
    }

    public record Context(UserAccount user, Ledger ledger, LedgerMember member) {
    }
}

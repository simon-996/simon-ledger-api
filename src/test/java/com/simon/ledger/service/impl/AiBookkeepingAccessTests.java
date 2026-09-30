package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiBookkeepingAccessTests {
    @Mock private UserAccountMapper users;
    @Mock private LedgerMapper ledgers;
    @Mock private LedgerMemberMapper members;

    @Test
    void deniesByDefaultBeforeLookingUpLedger() {
        UserAccount user = activeUser(false);
        when(users.selectById(7L)).thenReturn(user);
        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginId).thenReturn("7");
            BusinessException error = assertThrows(BusinessException.class,
                    () -> new AiBookkeepingAccess(users, ledgers, members).requireAllowed("ledger-1"));
            assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
        }
        verifyNoInteractions(ledgers, members);
    }

    @Test
    void requiresActiveWriterMembershipOnEveryCall() {
        UserAccount user = activeUser(true);
        Ledger ledger = new Ledger();
        ledger.setId(5L);
        ledger.setUuid("ledger-1");
        LedgerMember member = new LedgerMember();
        member.setStatus(1);
        member.setRole("viewer");
        when(users.selectById(7L)).thenReturn(user);
        when(ledgers.selectOne(any(Wrapper.class))).thenReturn(ledger);
        when(members.selectOne(any(Wrapper.class))).thenReturn(member);
        AiBookkeepingAccess access = new AiBookkeepingAccess(users, ledgers, members);
        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginId).thenReturn("7");
            assertEquals(ErrorCode.FORBIDDEN,
                    assertThrows(BusinessException.class, () -> access.requireAllowed("ledger-1")).getErrorCode());
            member.setRole("editor");
            assertEquals(5L, access.requireAllowed("ledger-1").ledger().getId());
            user.setAiBookkeepingEnabled(false);
            assertEquals(ErrorCode.FORBIDDEN,
                    assertThrows(BusinessException.class, () -> access.requireAllowed("ledger-1")).getErrorCode());
        }
    }

    private UserAccount activeUser(boolean enabled) {
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setStatus(1);
        user.setAiBookkeepingEnabled(enabled);
        return user;
    }
}

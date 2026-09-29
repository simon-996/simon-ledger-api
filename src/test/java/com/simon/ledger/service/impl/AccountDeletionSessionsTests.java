package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountDeletionSessionsTests {

    @Mock private JdbcTemplate jdbc;

    @Test
    void successfulRevokeRemovesDurableRetryRecord() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            new AccountDeletionSessions(jdbc).revoke(42L);
            stp.verify(() -> StpUtil.logout(42L));
        }
        verify(jdbc).update(startsWith("DELETE FROM account_session_revocation_queue"), eq(42L));
    }

    @Test
    void failedRevokeRemainsQueuedForScheduledRetry() {
        when(jdbc.queryForList(startsWith("SELECT login_id FROM account_session_revocation_queue"), eq(Long.class)))
                .thenReturn(List.of(42L));
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.logout(42L)).thenThrow(new IllegalStateException("Redis unavailable"));
            new AccountDeletionSessions(jdbc).retryPending();
        }
        verify(jdbc).update(startsWith("UPDATE account_session_revocation_queue"), eq(42L));
    }
}

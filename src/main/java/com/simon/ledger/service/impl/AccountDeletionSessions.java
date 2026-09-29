package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionSessions {

    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelayString = "${ledger.account-deletion.session-retry-ms:60000}")
    public void retryPending() {
        try {
            for (Long loginId : jdbc.queryForList(
                    "SELECT login_id FROM account_session_revocation_queue ORDER BY created_at LIMIT 100",
                    Long.class)) {
                revoke(loginId);
            }
        } catch (RuntimeException error) {
            log.error("Could not read pending account session revocations", error);
        }
    }

    public void revoke(Long userId) {
        try {
            StpUtil.logout(userId);
            jdbc.update("DELETE FROM account_session_revocation_queue WHERE login_id = ?", userId);
        } catch (RuntimeException error) {
            try {
                jdbc.update("UPDATE account_session_revocation_queue SET attempts = attempts + 1, last_attempt_at = NOW() WHERE login_id = ?",
                        userId);
            } catch (RuntimeException queueError) {
                error.addSuppressed(queueError);
            }
            // WebConfig rejects old tokens until the durable queue succeeds.
            log.error("Failed to revoke deleted account sessions; pending loginId={}", userId, error);
        }
    }
}

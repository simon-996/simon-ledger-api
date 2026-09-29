package com.simon.ledger.service.impl;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.AdminAccountDeletionReq;
import com.simon.ledger.dto.resp.AdminAccountDeletionPreviewResp;
import com.simon.ledger.dto.resp.AdminUserResp;
import com.simon.ledger.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opt-in against a disposable MySQL schema named simon_ledger_integration. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LEDGER_DELETION_TEST_DB_URL", matches = ".+")
class AdminAccountDeletionMySqlIntegrationTests {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("LEDGER_DELETION_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("LEDGER_DELETION_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("LEDGER_DELETION_TEST_DB_PASSWORD"));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminAccountDeletionService previewService;
    @Autowired private AdminAccountDeletionExecutor executor;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private AdminService adminService;
    @MockitoBean private AccountDeletionSessions sessions;

    private Long targetId;
    private Long successorId;
    private Long soloId;
    private Long sharedId;
    private Long joinedId;

    @BeforeEach
    void seed() {
        String url = System.getenv("LEDGER_DELETION_TEST_DB_URL");
        assertTrue(url.matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/simon_ledger_integration(\\?.*)?$"),
                "Integration tests require a disposable loopback-only MySQL instance");
        assertEquals("simon_ledger_integration", jdbc.queryForObject("SELECT DATABASE()", String.class),
                "Integration tests must never touch a shared database");
        jdbc.execute("DROP TABLE IF EXISTS deletion_test_guard");
        for (String table : List.of("account_session_revocation_queue", "admin_operation_log", "ledger_transaction_person", "ledger_invite",
                "ledger_change_log", "ledger_transaction", "ledger_person", "ledger_member", "ledger",
                "idempotency_record", "admin_user", "user_account")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("""
                INSERT INTO admin_user (uuid, account, password_hash, nickname, role, status)
                VALUES ('integration-admin', 'integration-admin', 'unused', 'Admin', 'super_admin', 1)
                """);
        AdminUserResp admin = new AdminUserResp();
        admin.setUuid("integration-admin");
        when(adminService.me()).thenReturn(admin);

        jdbc.update("INSERT INTO user_account (uuid, email, nickname) VALUES ('target', 'target@example.test', 'Target')");
        jdbc.update("INSERT INTO user_account (uuid, email, nickname) VALUES ('successor', 'next@example.test', 'Next')");
        jdbc.update("INSERT INTO user_account (uuid, email, nickname) VALUES ('other', 'other@example.test', 'Other')");
        targetId = id("user_account", "target");
        successorId = id("user_account", "successor");
        Long otherId = id("user_account", "other");

        jdbc.update("INSERT INTO ledger (uuid, name, base_currency_code, owner_user_id) VALUES ('solo', 'Solo', 'CNY', ?)", targetId);
        jdbc.update("INSERT INTO ledger (uuid, name, base_currency_code, owner_user_id) VALUES ('shared', 'Shared', 'CNY', ?)", targetId);
        jdbc.update("INSERT INTO ledger (uuid, name, base_currency_code, owner_user_id) VALUES ('joined', 'Joined', 'CNY', ?)", otherId);
        soloId = id("ledger", "solo");
        sharedId = id("ledger", "shared");
        joinedId = id("ledger", "joined");
        member("solo-target", soloId, targetId, "owner");
        member("shared-target", sharedId, targetId, "owner");
        member("shared-successor", sharedId, successorId, "editor");
        member("joined-target", joinedId, targetId, "viewer");
        member("joined-owner", joinedId, otherId, "owner");
        transaction("solo", soloId, 11);
        transaction("shared", sharedId, 22);
        transaction("joined", joinedId, 33);
        jdbc.update("INSERT INTO ledger_invite (uuid, ledger_id, code, role, created_by_user_id, expires_at) "
                + "VALUES ('invite-shared', ?, 'invite-shared', 'viewer', ?, DATE_ADD(NOW(), INTERVAL 1 DAY))",
                sharedId, targetId);
        jdbc.update("INSERT INTO idempotency_record (user_id, request_key, request_method, request_path, response_code, expires_at) "
                + "VALUES (?, 'key', 'POST', '/api/ledgers', 0, DATE_ADD(NOW(), INTERVAL 1 DAY))", targetId);
        jdbc.update("""
                INSERT INTO idempotency_record
                    (user_id, request_key, request_method, request_path, response_code, response_body, expires_at)
                VALUES (?, 'cached-person', 'POST', '/api/other-path', 0,
                    '{"linkedUserUuid":"target","avatar":"old-avatar"}', DATE_ADD(NOW(), INTERVAL 1 DAY))
                """, otherId);
        jdbc.update("""
                INSERT INTO idempotency_record
                    (user_id, request_key, request_method, request_path, response_code, response_body, expires_at)
                VALUES (?, 'cached-ledger', 'POST', '/api/ledgers/shared/people', 0,
                    '{"name":"Target","avatar":"old-avatar"}', DATE_ADD(NOW(), INTERVAL 1 DAY))
                """, otherId);
        jdbc.update("""
                INSERT INTO idempotency_record
                    (user_id, request_key, request_method, request_path, response_code, response_body, expires_at)
                VALUES (?, 'cached-invite', 'POST', '/api/invites/invite-shared/join', 0,
                    '{"nickname":"Target"}', DATE_ADD(NOW(), INTERVAL 1 DAY))
                """, otherId);
        jdbc.update("""
                INSERT INTO idempotency_record
                    (user_id, request_key, request_method, request_path, response_code, response_body, expires_at)
                VALUES (?, 'unrelated', 'POST', '/api/other-path', 0,
                    '{"uuid":"unrelated"}', DATE_ADD(NOW(), INTERVAL 1 DAY))
                """, otherId);
    }

    @Test
    void deletesSoloLedgerButPreservesAndTransfersSharedHistory() {
        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        executor.delete("target", request(preview.getFingerprint(), true));

        assertEquals(0, count("user_account", "uuid = 'target'"));
        assertEquals(0, count("ledger", "uuid = 'solo'"));
        assertEquals(0, count("ledger_transaction", "uuid = 'tx-solo'"));
        assertEquals(2, count("ledger_transaction_person", "1 = 1"));
        assertEquals(successorId, jdbc.queryForObject("SELECT owner_user_id FROM ledger WHERE uuid = 'shared'", Long.class));
        assertEquals("owner", jdbc.queryForObject("SELECT role FROM ledger_member WHERE uuid = 'shared-successor'", String.class));
        assertEquals(0, count("ledger_member", "user_id = " + targetId));
        assertEquals(0, count("ledger_invite", "uuid = 'invite-shared'"));
        assertEquals(0, count("idempotency_record", "user_id = " + targetId));
        assertEquals(-2, jdbc.queryForObject("SELECT response_code FROM idempotency_record WHERE request_key = 'cached-person'", Integer.class));
        assertNull(jdbc.queryForObject("SELECT response_body FROM idempotency_record WHERE request_key = 'cached-person'", String.class));
        assertEquals(-2, jdbc.queryForObject("SELECT response_code FROM idempotency_record WHERE request_key = 'cached-ledger'", Integer.class));
        assertEquals(-2, jdbc.queryForObject("SELECT response_code FROM idempotency_record WHERE request_key = 'cached-invite'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT response_code FROM idempotency_record WHERE request_key = 'unrelated'", Integer.class));
        for (String suffix : List.of("shared", "joined")) {
            assertEquals(new BigDecimal(suffix.equals("shared") ? "22.00" : "33.00"),
                    jdbc.queryForObject("SELECT amount FROM ledger_transaction WHERE uuid = ?", BigDecimal.class, "tx-" + suffix));
            assertNull(jdbc.queryForObject("SELECT created_by_user_id FROM ledger_transaction WHERE uuid = ?", Long.class, "tx-" + suffix));
            assertNull(jdbc.queryForObject("SELECT last_modified_by_user_id FROM ledger_transaction WHERE uuid = ?", Long.class, "tx-" + suffix));
            assertNull(jdbc.queryForObject("SELECT linked_user_id FROM ledger_person WHERE uuid = ?", Long.class, "person-" + suffix));
            assertEquals("", jdbc.queryForObject("SELECT avatar FROM ledger_person WHERE uuid = ?", String.class, "person-" + suffix));
            assertTrue(count("ledger_change_log", "ledger_id = " + (suffix.equals("shared") ? sharedId : joinedId)) >= 3);
        }
        assertEquals(1, count("admin_operation_log", "action = 'delete_account' AND target_uuid IS NULL"));
        assertEquals(0, count("admin_operation_log", "detail LIKE '%target%'"));
        assertEquals(1, count("account_session_revocation_queue", "login_id = " + targetId));
        verify(sessions).revoke(targetId);
    }

    @Test
    void stalePreviewRejectsAllChanges() {
        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        jdbc.update("UPDATE ledger_member SET version = version + 1 WHERE uuid = 'shared-successor'");
        BusinessException error = assertThrows(BusinessException.class,
                () -> executor.delete("target", request(preview.getFingerprint(), true)));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(1, count("user_account", "uuid = 'target'"));
        assertEquals(targetId, jdbc.queryForObject("SELECT owner_user_id FROM ledger WHERE uuid = 'shared'", Long.class));
    }

    @Test
    void missingSuccessorRejectsAllChanges() {
        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        BusinessException error = assertThrows(BusinessException.class,
                () -> executor.delete("target", request(preview.getFingerprint(), false)));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(1, count("user_account", "uuid = 'target'"));
        assertEquals(1, count("ledger", "uuid = 'solo'"));
    }

    @Test
    void softDeletedOwnedLedgerIsPhysicallyRemovedInsteadOfTransferred() {
        jdbc.update("INSERT INTO ledger (uuid, name, base_currency_code, owner_user_id, deleted_at) "
                + "VALUES ('archived', 'Archived', 'CNY', ?, NOW())", targetId);
        Long archivedId = id("ledger", "archived");
        member("archived-target", archivedId, targetId, "owner");
        member("archived-successor", archivedId, successorId, "editor");
        transaction("archived", archivedId, 44);

        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        assertEquals("DELETE", preview.getOwnedLedgers().stream()
                .filter(ledger -> ledger.getUuid().equals("archived")).findFirst().orElseThrow().getAction());
        executor.delete("target", request(preview.getFingerprint(), true));
        assertEquals(0, count("ledger", "uuid = 'archived'"));
        assertEquals(0, count("ledger_transaction", "uuid = 'tx-archived'"));
        assertEquals(0, count("ledger_member", "uuid = 'archived-successor'"));
    }

    @Test
    void foreignKeyFailureAfterTransferRollsBackEverything() {
        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        jdbc.execute("""
                CREATE TABLE deletion_test_guard (
                    user_id BIGINT PRIMARY KEY,
                    CONSTRAINT fk_deletion_test_guard FOREIGN KEY (user_id) REFERENCES user_account(id)
                )
                """);
        jdbc.update("INSERT INTO deletion_test_guard (user_id) VALUES (?)", targetId);
        try {
            assertThrows(RuntimeException.class,
                    () -> executor.delete("target", request(preview.getFingerprint(), true)));
            assertEquals(1, count("user_account", "uuid = 'target'"));
            assertEquals(1, count("ledger_transaction", "uuid = 'tx-solo'"));
            assertEquals(1, count("ledger_transaction_person", "transaction_id = " + id("ledger_transaction", "tx-solo")));
            assertEquals(targetId, jdbc.queryForObject("SELECT owner_user_id FROM ledger WHERE uuid = 'shared'", Long.class));
            assertEquals(1, count("ledger_member", "uuid = 'shared-target'"));
            assertEquals(targetId, jdbc.queryForObject("SELECT created_by_user_id FROM ledger_transaction WHERE uuid = 'tx-joined'", Long.class));
        } finally {
            jdbc.execute("DROP TABLE deletion_test_guard");
        }
    }

    @Test
    void memberJoiningWhileDeletionWaitsForLedgerLockInvalidatesPreview() throws Exception {
        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<BusinessException>> deletion = new AtomicReference<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM ledger WHERE id = ? FOR UPDATE", Long.class, soloId);
                deletion.set(worker.submit(() -> {
                    try {
                        executor.delete("target", request(preview.getFingerprint(), true));
                        return null;
                    } catch (BusinessException error) {
                        return error;
                    }
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l
                          ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA = 'simon_ledger_integration' AND l.OBJECT_NAME = 'ledger'
                        """, Integer.class) == 0) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("Deletion did not reach the ledger row lock");
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                }
                member("solo-successor", soloId, successorId, "editor");
            });
            BusinessException error = deletion.get().get(10, TimeUnit.SECONDS);
            assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
            assertEquals(1, count("ledger", "uuid = 'solo'"));
            assertEquals(1, count("ledger_member", "uuid = 'solo-successor'"));
            assertEquals(1, count("user_account", "uuid = 'target'"));
        } finally {
            worker.shutdownNow();
        }
    }

    private AdminAccountDeletionReq request(String fingerprint, boolean withSuccessor) {
        AdminAccountDeletionReq req = new AdminAccountDeletionReq();
        req.setFingerprint(fingerprint);
        req.setConfirmUuid("target");
        if (withSuccessor) {
            AdminAccountDeletionReq.Successor successor = new AdminAccountDeletionReq.Successor();
            successor.setLedgerUuid("shared");
            successor.setUserUuid("successor");
            req.setSuccessors(List.of(successor));
        } else {
            req.setSuccessors(List.of());
        }
        return req;
    }

    private void member(String uuid, Long ledgerId, Long userId, String role) {
        jdbc.update("INSERT INTO ledger_member (uuid, ledger_id, user_id, role) VALUES (?, ?, ?, ?)",
                uuid, ledgerId, userId, role);
    }

    private void transaction(String suffix, Long ledgerId, int amount) {
        jdbc.update("INSERT INTO ledger_person (uuid, ledger_id, linked_user_id, name, avatar) VALUES (?, ?, ?, 'Target', 'old-avatar')",
                "person-" + suffix, ledgerId, targetId);
        Long personId = id("ledger_person", "person-" + suffix);
        jdbc.update("""
                INSERT INTO ledger_transaction (uuid, ledger_id, type, payer_person_id, amount,
                    currency_code, category, created_by_user_id, last_modified_by_user_id, happened_at)
                VALUES (?, ?, 0, ?, ?, 'CNY', 'food', ?, ?, NOW())
                """, "tx-" + suffix, ledgerId, personId, amount, targetId, targetId);
        Long transactionId = id("ledger_transaction", "tx-" + suffix);
        jdbc.update("INSERT INTO ledger_transaction_person (transaction_id, person_id, share_amount) VALUES (?, ?, ?)",
                transactionId, personId, amount);
        jdbc.update("""
                INSERT INTO ledger_change_log (uuid, ledger_id, entity_type, entity_uuid, operation, operator_user_id, version)
                VALUES (?, ?, 'transaction', ?, 'create', ?, 1)
                """, "log-" + suffix, ledgerId, "tx-" + suffix, targetId);
    }

    private Long id(String table, String uuid) {
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE uuid = ?", Long.class, uuid);
    }

    private int count(String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class);
    }
}

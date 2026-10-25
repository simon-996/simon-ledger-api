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
    @Autowired private com.simon.ledger.service.ChangeLogService changeLogs;
    @Autowired private com.simon.ledger.service.IdempotencyService idempotency;
    @Autowired private com.simon.ledger.service.LedgerService ledgerService;
    @Autowired private com.simon.ledger.service.AuthService authService;
    @Autowired private com.simon.ledger.service.TransactionService transactionService;
    @Autowired private com.simon.ledger.mapper.LedgerMapper ledgerMapper;
    @Autowired private com.simon.ledger.service.InviteService inviteService;

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

    static class UnserializableResponse { public UnserializableResponse getSelf() { return this; } }

    @Test
    void responseSerializationFailureRollsBackBusinessAndReservationThenRetryCommitsOnce() {
        withLogin(successorId, () -> {
            assertThrows(BusinessException.class, () -> idempotency.execute("atomic-create", "POST", "/api/ledgers", Object.class,
                    () -> { ledgerService.create(createLedgerRequest("atomic")); return new UnserializableResponse(); }));
            assertEquals(0, count("ledger", "name = 'atomic'"));
            assertEquals(0, count("idempotency_record", "request_key = 'atomic-create'"));
            var saved = idempotency.execute("atomic-create", "POST", "/api/ledgers", com.simon.ledger.dto.resp.LedgerResp.class,
                    () -> ledgerService.create(createLedgerRequest("atomic")));
            var replay = idempotency.execute("atomic-create", "POST", "/api/ledgers", com.simon.ledger.dto.resp.LedgerResp.class,
                    () -> { throw new AssertionError("must replay"); });
            assertEquals(saved.getUuid(), replay.getUuid());
            assertEquals(1, count("ledger", "name = 'atomic'"));
        });
    }

    @Test
    void profileAndTransactionCreationParticipateInOuterIdempotencyRollback() {
        withLogin(targetId, () -> {
            var profile = new com.simon.ledger.dto.req.AuthProfileUpdateReq(); profile.setVersion(1); profile.setNickname("changed"); profile.setAvatar("");
            assertThrows(BusinessException.class, () -> idempotency.execute("profile-atomic", "PUT", "/api/auth/me", Object.class,
                    () -> { authService.updateProfile(profile); return new UnserializableResponse(); }));
            assertEquals("Target", jdbc.queryForObject("SELECT nickname FROM user_account WHERE id = ?", String.class, targetId));
            assertEquals(1, jdbc.queryForObject("SELECT version FROM user_account WHERE id = ?", Integer.class, targetId));
            var tx = new com.simon.ledger.dto.req.TransactionCreateReq();
            tx.setType(0); tx.setAmount(new BigDecimal("1.01")); tx.setCurrencyCode("CNY"); tx.setCategory("food");
            tx.setPersonUuids(List.of("person-shared")); tx.setClientOperationId("atomic-tx"); tx.setHappenedAt(java.time.LocalDateTime.now());
            assertThrows(BusinessException.class, () -> idempotency.execute("transaction-atomic", "POST", "/api/ledgers/shared/transactions", Object.class,
                    () -> { transactionService.create("shared", tx); return new UnserializableResponse(); }));
            assertEquals(0, count("ledger_transaction", "client_operation_id = 'atomic-tx'"));
        });
    }

    @Test
    void concurrentSameKeyCreatesOneLedgerAndLosingSpeculationRollsBack() throws Exception {
        var barrier = new java.util.concurrent.CountDownLatch(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Void> create = () -> {
                withLogin(successorId, () -> {
                    try {
                        idempotency.execute("same-key", "POST", "/api/ledgers", com.simon.ledger.dto.resp.LedgerResp.class, () -> {
                            var result = ledgerService.create(createLedgerRequest("same-key")); barrier.countDown(); await(barrier); return result;
                        });
                    } catch (BusinessException error) { assertEquals(ErrorCode.CONFLICT, error.getErrorCode()); }
                }); return null;
            };
            var first = workers.submit(create); var second = workers.submit(create);
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals(1, count("ledger", "name = 'same-key'"));
            assertEquals(1, count("idempotency_record", "request_key = 'same-key' AND response_code = 0"));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void originalUsdTransactionRemainsEditableAfterBaseChangeEvenWhenLegacyRateIsMissing() {
        jdbc.update("UPDATE ledger SET base_currency_code='EUR',exchange_rate_to_cny=8,currency_rates_to_cny=? WHERE id=?",
                "{\"CNY\":\"1\",\"USD\":\"7\",\"EUR\":\"8\"}", sharedId);
        jdbc.update("UPDATE ledger_transaction SET currency_code='USD',amount=100 WHERE uuid='tx-shared'");
        withLogin(targetId, () -> {
            var update = new com.simon.ledger.dto.req.TransactionUpdateReq(); update.setVersion(1); update.setType(0);
            update.setAmount(new BigDecimal("100.00")); update.setCurrencyCode("USD"); update.setCategory("food");
            update.setPersonUuids(List.of("person-shared")); update.setPayerPersonUuid("person-shared");
            update.setHappenedAt(java.time.LocalDateTime.now()); update.setNote("retained USD");
            var first = transactionService.update("shared", "tx-shared", update);
            assertEquals("USD",first.getCurrencyCode()); assertEquals(new BigDecimal("100.00"), first.getAmount());
            jdbc.update("UPDATE ledger SET currency_rates_to_cny='{\"CNY\":\"1\",\"EUR\":\"8\"}' WHERE id=?", sharedId);
            update.setVersion(first.getVersion()); update.setNote("legacy USD without a known rate");
            var second = transactionService.update("shared", "tx-shared", update);
            assertEquals("USD",second.getCurrencyCode()); assertEquals(new BigDecimal("100.00"),second.getAmount());
            assertEquals("legacy USD without a known rate",second.getNote());
            assertEquals("USD",jdbc.queryForObject("SELECT currency_code FROM ledger_transaction WHERE uuid='tx-shared'",String.class));
        });
    }

    @Test
    void defaultAnd255CharacterAvatarsCanJoinRepeatedlyWithoutNullWrites() {
        for (String avatar : new String[]{null, "https://example.test/" + "x".repeat(255 - "https://example.test/".length())}) {
            var request = new com.simon.ledger.dto.req.AuthRegisterReq(); request.setEmail(avatar==null ? "default-avatar@example.test" : "long-avatar@example.test");
            request.setNickname("Avatar"); request.setPassword("password"); request.setAvatar(avatar);
            var user = authService.register(request);
            withLogin(id("user_account",user.getUuid()), () -> {
                var first = inviteService.join("invite-shared");
                var second = inviteService.join("invite-shared");
                assertEquals(avatar==null ? "" : avatar,first.getPerson().getAvatar());
                assertEquals(first.getPerson().getUuid(),second.getPerson().getUuid());
                assertEquals(first.getPerson().getVersion(),second.getPerson().getVersion());
            });
        }
    }

    @Test
    void legacyCrossColumnCollisionDoesNotBreakOriginalEmailLoginAndCannotBeRegisteredAgain() {
        jdbc.update("UPDATE user_account SET password_hash = ? WHERE id = ?", cn.hutool.crypto.digest.BCrypt.hashpw("password"), targetId);
        jdbc.update("UPDATE user_account SET phone = 'target@example.test' WHERE id = ?", successorId);
        try (var login = org.mockito.Mockito.mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            var request = new com.simon.ledger.dto.req.AuthLoginReq(); request.setAccount("target@example.test"); request.setPassword("password");
            assertEquals("target", authService.login(request).getUser().getUuid());
        }
        var register = new com.simon.ledger.dto.req.AuthRegisterReq(); register.setNickname("new"); register.setPassword("password"); register.setPhone("target@example.test");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class, () -> authService.register(register)).getErrorCode());
        jdbc.update("UPDATE user_account SET phone = 'legacy@example.test' WHERE id = ?", successorId);
        register.setPhone(null); register.setEmail("legacy@example.test");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class, () -> authService.register(register)).getErrorCode());
    }

    @Test
    void idempotencyReplayPreservesLargeAmountsAndAllRateDecimals() {
        withLogin(successorId, () -> {
            var original = new com.simon.ledger.dto.resp.LedgerResp(); original.setUuid("exact-rate");
            original.setExchangeRateToCny(new BigDecimal("9999999999.12345678"));
            original.setCurrencyRatesToCny(java.util.Map.of("USD", original.getExchangeRateToCny()));
            idempotency.execute("exact-rate", "POST", "/api/ledgers", com.simon.ledger.dto.resp.LedgerResp.class, () -> original);
            var replay = idempotency.execute("exact-rate", "POST", "/api/ledgers", com.simon.ledger.dto.resp.LedgerResp.class,
                    () -> { throw new AssertionError("must replay"); });
            assertEquals(original.getExchangeRateToCny(), replay.getExchangeRateToCny());
            assertEquals(original.getCurrencyRatesToCny(), replay.getCurrencyRatesToCny());
        });
    }

    @Test
    void deletionWaitsForAtomicLedgerResponseThenInvalidatesIt() throws Exception {
        jdbc.update("UPDATE ledger_member SET role = 'admin' WHERE uuid = 'shared-successor'");
        var preview = previewService.preview("target");
        var businessFinished = new java.util.concurrent.CountDownLatch(1);
        var allowCompletion = new java.util.concurrent.CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var write = workers.submit(() -> withLogin(successorId, () -> {
                var request = new com.simon.ledger.dto.req.LedgerUpdateReq(); request.setVersion(1); request.setName("Shared");
                request.setBaseCurrencyCode("CNY"); request.setExchangeRateToCny(BigDecimal.ONE);
                idempotency.execute("deletion-race", "PUT", "/api/ledgers/shared", com.simon.ledger.dto.resp.LedgerResp.class, () -> {
                    var response = ledgerService.update("shared", request); businessFinished.countDown(); await(allowCompletion); return response;
                });
            }));
            assertTrue(businessFinished.await(5, TimeUnit.SECONDS));
            var deletion = workers.submit(() -> {
                try { executor.delete("target", request(preview.getFingerprint(), true)); return false; }
                catch (BusinessException error) { assertEquals(ErrorCode.CONFLICT, error.getErrorCode()); return true; }
            });
            // Releasing the writer commits business and response atomically. Deletion may
            // reject its now-stale preview; retry with a fresh fingerprint before checking cache.
            allowCompletion.countDown(); write.get(10, TimeUnit.SECONDS);
            if (deletion.get(10, TimeUnit.SECONDS)) executor.delete("target", request(previewService.preview("target").getFingerprint(), true));
            assertEquals(-2, jdbc.queryForObject("SELECT response_code FROM idempotency_record WHERE request_key = 'deletion-race'", Integer.class));
            assertNull(jdbc.queryForObject("SELECT response_body FROM idempotency_record WHERE request_key = 'deletion-race'", String.class));
        } finally { allowCompletion.countDown(); workers.shutdownNow(); }
    }

    @Test
    void jsonRateStoragePreservesAllDecimal18Digits() {
        withLogin(successorId, () -> {
            var request = createLedgerRequest("precise-rate");
            request.setCurrencyRatesToCny(java.util.Map.of("USD", new BigDecimal("9999999999.12345678")));
            var created = ledgerService.create(request);
            assertEquals(0, new BigDecimal("9999999999.12345678").compareTo(ledgerService.detail(created.getUuid()).getCurrencyRatesToCny().get("USD")));
        });
    }

    @Test
    void rateMapPersistsThroughEntityInsertAndWrapperUpdateWithoutLosingLegacyBase() {
        withLogin(successorId, () -> {
            var request = createLedgerRequest("rates"); request.setBaseCurrencyCode("USD"); request.setExchangeRateToCny(new BigDecimal("7"));
            request.setCurrencyRatesToCny(java.util.Map.of("EUR", new BigDecimal("8")));
            var created = ledgerService.create(request);
            var stored = ledgerMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.simon.ledger.entity.Ledger>lambdaQuery()
                    .eq(com.simon.ledger.entity.Ledger::getUuid, created.getUuid()));
            assertEquals(0, new BigDecimal("7").compareTo(stored.getCurrencyRatesToCny().get("USD")));
            var update = new com.simon.ledger.dto.req.LedgerUpdateReq(); update.setVersion(1); update.setName("rates");
            update.setBaseCurrencyCode("EUR"); update.setExchangeRateToCny(new BigDecimal("8"));
            ledgerService.update(created.getUuid(), update);
            var detail = ledgerService.detail(created.getUuid());
            assertEquals(0, new BigDecimal("7").compareTo(detail.getCurrencyRatesToCny().get("USD")));
            assertEquals(0, new BigDecimal("8").compareTo(detail.getCurrencyRatesToCny().get("EUR")));
        });
    }

    private com.simon.ledger.dto.req.LedgerCreateReq createLedgerRequest(String name) {
        var request = new com.simon.ledger.dto.req.LedgerCreateReq(); request.setName(name); request.setBaseCurrencyCode("CNY"); request.setExchangeRateToCny(BigDecimal.ONE); return request;
    }
    private void withLogin(Long userId, Runnable action) {
        try (var login = org.mockito.Mockito.mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            login.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsLong).thenReturn(userId); action.run();
        }
    }

    @Test
    void changelogAllocationUsesCurrentReadAfterRepeatableReadSnapshot() throws Exception {
        java.util.concurrent.CountDownLatch snapshotTaken = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch writerCommitted = new java.util.concurrent.CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> waitingWriter = workers.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                tx.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT COUNT(*) FROM ledger_change_log WHERE ledger_id = ?", Integer.class, sharedId);
                    snapshotTaken.countDown();
                    await(writerCommitted);
                    jdbc.queryForObject("SELECT id FROM ledger WHERE id = ? FOR UPDATE", Long.class, sharedId);
                    changeLogs.record(sharedId, "ledger", "shared", "update", successorId);
                });
            });
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS));
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM ledger WHERE id = ? FOR UPDATE", Long.class, sharedId);
                changeLogs.record(sharedId, "ledger", "shared", "update", successorId);
            });
            writerCommitted.countDown();
            waitingWriter.get(5, TimeUnit.SECONDS);
            assertEquals(3, count("ledger_change_log", "ledger_id = " + sharedId));
        } finally { writerCommitted.countDown(); workers.shutdownNow(); }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test barrier"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test
    void deletionInvalidatesProcessingResponsesAndAnonymizesPriorAiAudits() {
        jdbc.update("INSERT INTO admin_operation_log (uuid, admin_user_id, action, target_type, target_uuid, detail) "
                + "SELECT 'prior-ai', id, 'ai_bookkeeping_grant', 'user_account', 'target', '授权 AI 记账' FROM admin_user LIMIT 1");
        jdbc.update("INSERT INTO idempotency_record (user_id, request_key, request_method, request_path, response_code, expires_at) "
                + "VALUES (?, 'processing', 'PUT', '/api/ledgers/shared', -1, DATE_ADD(NOW(), INTERVAL 1 DAY))", successorId);
        AdminAccountDeletionPreviewResp preview = previewService.preview("target");
        executor.delete("target", request(preview.getFingerprint(), true));
        assertNull(jdbc.queryForObject("SELECT target_uuid FROM admin_operation_log WHERE uuid = 'prior-ai'", String.class));
        assertEquals(-2, jdbc.queryForObject("SELECT response_code FROM idempotency_record WHERE request_key = 'processing'", Integer.class));
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

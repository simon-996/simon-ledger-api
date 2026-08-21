package com.simon.ledger.concurrency;

import com.simon.ledger.dto.req.AuthProfileUpdateReq;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.req.TransactionUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.AuthUserResp;
import com.simon.ledger.dto.resp.LedgerMemberSummaryResp;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.UserAccount;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionContractTests {

    @Test
    void mutableEntitiesExposeVersionDefaultingToOne() throws Exception {
        for (Class<?> type : List.of(UserAccount.class, Ledger.class, LedgerMember.class,
                LedgerPerson.class, LedgerTransaction.class)) {
            Field version = type.getDeclaredField("version");
            version.setAccessible(true);
            assertEquals(Integer.class, version.getType(), type.getSimpleName());
            assertEquals(1, version.get(type.getDeclaredConstructor().newInstance()), type.getSimpleName());
        }
    }

    @Test
    void updateRequestsExposeIntegerVersion() throws Exception {
        for (Class<?> type : List.of(AuthProfileUpdateReq.class, LedgerUpdateReq.class,
                MemberRoleUpdateReq.class, PersonUpdateReq.class, TransactionUpdateReq.class, VersionDeleteReq.class)) {
            Field version = type.getDeclaredField("version");
            assertEquals(Integer.class, version.getType(), type.getSimpleName());
            NotNull notNull = version.getAnnotation(NotNull.class);
            assertEquals("版本号不能为空", notNull.message(), type.getSimpleName());
            Min min = version.getAnnotation(Min.class);
            assertEquals(1, min.value(), type.getSimpleName());
            assertEquals("版本号必须大于 0", min.message(), type.getSimpleName());
        }
    }

    @Test
    void publicResponsesExposeIntegerVersion() throws Exception {
        for (Class<?> type : List.of(AuthUserResp.class, LedgerResp.class, LedgerMemberSummaryResp.class,
                MemberResp.class, PersonResp.class)) {
            assertEquals(Integer.class, type.getDeclaredField("version").getType(), type.getSimpleName());
        }
    }

    @Test
    void versionMutationResponseHasExactContract() throws Exception {
        assertEquals(String.class, VersionMutationResp.class.getDeclaredField("uuid").getType());
        assertEquals(Integer.class, VersionMutationResp.class.getDeclaredField("version").getType());
        assertEquals(Boolean.class, VersionMutationResp.class.getDeclaredField("deleted").getType());
        VersionMutationResp response = new VersionMutationResp("u", 2, true);
        assertEquals("u", response.getUuid());
        assertEquals(2, response.getVersion());
        assertEquals(true, response.getDeleted());
    }

    @Test
    void versionMigrationOnlyAddsVersionsToMutableNonTransactionTables() throws Exception {
        String migration = Files.readString(Path.of("sql", "004_add_optimistic_versions.sql"));
        assertTrue(migration.contains("ALTER TABLE user_account ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status;"));
        assertTrue(migration.contains("ALTER TABLE ledger ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER owner_user_id;"));
        assertTrue(migration.contains("ALTER TABLE ledger_member ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status;"));
        assertTrue(migration.contains("ALTER TABLE ledger_person ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER avatar;"));
        assertTrue(!migration.contains("ALTER TABLE ledger_transaction"));
    }

    @Test
    void initialSchemaDeclaresVersionsForAllMutableTables() throws Exception {
        String schema = Files.readString(Path.of("sql", "001_init_schema.sql"));
        assertTrue(schema.matches("(?s).*CREATE TABLE IF NOT EXISTS user_account.*?version\\s+INT\\s+NOT NULL\\s+DEFAULT 1.*"));
        assertTrue(schema.matches("(?s).*CREATE TABLE IF NOT EXISTS ledger\\s*\\(.*?version\\s+INT\\s+NOT NULL\\s+DEFAULT 1.*"));
        assertTrue(schema.matches("(?s).*CREATE TABLE IF NOT EXISTS ledger_member.*?version\\s+INT\\s+NOT NULL\\s+DEFAULT 1.*"));
        assertTrue(schema.matches("(?s).*CREATE TABLE IF NOT EXISTS ledger_person.*?version\\s+INT\\s+NOT NULL\\s+DEFAULT 1.*"));
        assertTrue(schema.matches("(?s).*CREATE TABLE IF NOT EXISTS ledger_transaction.*?version\\s+INT\\s+NOT NULL\\s+DEFAULT 1.*"));
    }

    @Test
    void parameterizedFixturesPopulateCallerSuppliedValues() {
        assertEquals(7L, ConcurrencyFixtures.user(7, "u", "n", 3).getId());
        assertEquals(3, ConcurrencyFixtures.user(7, "u", "n", 3).getVersion());
        assertEquals(9, ConcurrencyFixtures.profileReq("n", 9).getVersion());
        assertEquals(8L, ConcurrencyFixtures.ledger(8, "l").getId());
        assertEquals(4, ConcurrencyFixtures.ledger(8, "l", 4, null).getVersion());
        assertEquals(2L, ConcurrencyFixtures.ownerMember(1, 2).getUserId());
        assertEquals(5, ConcurrencyFixtures.deleteReq(5).getVersion());
        assertEquals(6, ConcurrencyFixtures.person(3, "p", 6, null).getVersion());
        assertEquals(7, ConcurrencyFixtures.personReq("p", 7).getVersion());
        assertEquals(8, ConcurrencyFixtures.transaction(4, "t", 2, 8, null).getVersion());
    }
}

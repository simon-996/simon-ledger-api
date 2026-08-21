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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        List<String> statements = List.of(migration.replaceAll("(?m)--.*$", "").replaceAll("/\\*.*?\\*/", "")
                .split(";"));
        statements = statements.stream().map(s -> s.trim().replaceAll("\\s+", " ")).filter(s -> !s.isEmpty()).toList();
        assertEquals(List.of("USE simon_ledger", "ALTER TABLE user_account ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status",
                "ALTER TABLE ledger ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER owner_user_id",
                "ALTER TABLE ledger_member ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status",
                "ALTER TABLE ledger_person ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER avatar"), statements);
    }

    @Test
    void initialSchemaDeclaresVersionsForAllMutableTables() throws Exception {
        String schema = Files.readString(Path.of("sql", "001_init_schema.sql"));
        for (String table : List.of("user_account", "ledger", "ledger_member", "ledger_person", "ledger_transaction")) {
            Matcher start = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?ENGINE\\s*=\\s*InnoDB\\s*;?").matcher(schema);
            assertTrue(start.find(), table);
            String block = start.group();
            assertEquals(1, block.split("(?i)version\\s+INT\\s+NOT NULL\\s+DEFAULT 1", -1).length - 1, table);
        }
    }

    @Test
    void parameterizedFixturesPopulateCallerSuppliedValues() {
        assertEquals(7L, ConcurrencyFixtures.user(7, "u", "n", 3).getId());
        assertEquals(3, ConcurrencyFixtures.user(7, "u", "n", 3).getVersion());
        assertEquals(9, ConcurrencyFixtures.profileReq("n", 9).getVersion());
        assertEquals("", ConcurrencyFixtures.profileReq("n", 9).getAvatar());
        assertEquals(8L, ConcurrencyFixtures.ledger(8, "l").getId());
        assertEquals("测试账本", ConcurrencyFixtures.ledger(8, "l").getName());
        assertEquals(7L, ConcurrencyFixtures.ledger(8, "l").getOwnerUserId());
        assertEquals(4, ConcurrencyFixtures.ledger(8, "l", 4, null).getVersion());
        var member = ConcurrencyFixtures.ownerMember(1, 2);
        assertEquals(43L, member.getId());
        assertEquals("m-2", member.getUuid());
        assertEquals(1L, member.getLedgerId());
        assertEquals(2L, member.getUserId());
        assertEquals("owner", member.getRole());
        assertEquals(1, member.getStatus());
        assertEquals(1, member.getVersion());
        assertEquals(5, ConcurrencyFixtures.deleteReq(5).getVersion());
        var person = ConcurrencyFixtures.person(3, "p", 6, null);
        assertEquals(11L, person.getLedgerId());
        assertEquals("测试参与人", person.getName());
        assertEquals("", person.getAvatar());
        assertEquals(6, person.getVersion());
        assertEquals(7, ConcurrencyFixtures.personReq("p", 7).getVersion());
        var transaction = ConcurrencyFixtures.transaction(4, "t", 2, 8, null);
        assertEquals(11L, transaction.getLedgerId());
        assertEquals(2L, transaction.getCreatedByUserId());
        assertEquals(8, transaction.getVersion());
    }
}

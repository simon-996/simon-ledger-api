package com.simon.ledger.concurrency;

import com.simon.ledger.dto.req.AuthProfileUpdateReq;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.req.TransactionUpdateReq;
import com.simon.ledger.dto.resp.AuthUserResp;
import com.simon.ledger.dto.resp.LedgerMemberSummaryResp;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.PersonResp;
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
                MemberRoleUpdateReq.class, PersonUpdateReq.class, TransactionUpdateReq.class)) {
            assertEquals(Integer.class, type.getDeclaredField("version").getType(), type.getSimpleName());
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
    void versionMigrationOnlyAddsVersionsToMutableNonTransactionTables() throws Exception {
        String migration = Files.readString(Path.of("sql", "004_add_optimistic_versions.sql"));
        assertTrue(migration.contains("ALTER TABLE user_account ADD COLUMN version"));
        assertTrue(migration.contains("ALTER TABLE ledger ADD COLUMN version"));
        assertTrue(migration.contains("ALTER TABLE ledger_member ADD COLUMN version"));
        assertTrue(migration.contains("ALTER TABLE ledger_person ADD COLUMN version"));
        assertTrue(!migration.contains("ALTER TABLE ledger_transaction"));
    }
}

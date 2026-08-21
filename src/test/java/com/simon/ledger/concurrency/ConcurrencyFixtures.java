package com.simon.ledger.concurrency;

import com.simon.ledger.dto.req.AuthProfileUpdateReq;
import com.simon.ledger.dto.req.AuthRegisterReq;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.UserAccount;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Shared deterministic records for concurrency contract tests. */
public final class ConcurrencyFixtures {
    private ConcurrencyFixtures() { }

    public static UserAccount userAccount() {
        UserAccount user = new UserAccount();
        user.setUuid("user-uuid"); user.setNickname("Test User"); user.setStatus(1);
        return user;
    }

    public static AuthRegisterReq profileRequest() {
        AuthRegisterReq req = new AuthRegisterReq();
        req.setNickname("Test User"); req.setPassword("password"); req.setEmail("test@example.com");
        return req;
    }

    public static AuthProfileUpdateReq authProfileUpdateReq() {
        AuthProfileUpdateReq req = new AuthProfileUpdateReq();
        req.setVersion(1); req.setNickname("Updated User");
        return req;
    }

    public static Ledger ledger() {
        Ledger ledger = new Ledger();
        ledger.setUuid("ledger-uuid"); ledger.setName("Test Ledger"); ledger.setBaseCurrencyCode("CNY");
        ledger.setExchangeRateToCny(BigDecimal.ONE); ledger.setOwnerUserId(1L);
        return ledger;
    }

    public static LedgerUpdateReq ledgerUpdateReq() {
        LedgerUpdateReq req = new LedgerUpdateReq();
        req.setVersion(1); req.setName("Updated Ledger"); req.setBaseCurrencyCode("CNY");
        req.setExchangeRateToCny(BigDecimal.ONE);
        return req;
    }

    public static LedgerMember ownerMember() { return member("owner"); }
    public static LedgerMember adminMember() { return member("admin"); }
    public static LedgerMember genericMember() { return member("editor"); }

    private static LedgerMember member(String role) {
        LedgerMember member = new LedgerMember();
        member.setUuid("member-" + role); member.setLedgerId(1L); member.setUserId(1L);
        member.setRole(role); member.setStatus(1); member.setJoinedAt(LocalDateTime.now());
        return member;
    }

    public static VersionDeleteReq versionDeleteReq() {
        VersionDeleteReq req = new VersionDeleteReq(); req.setVersion(1); return req;
    }

    public static LedgerPerson ledgerPerson() {
        LedgerPerson person = new LedgerPerson();
        person.setUuid("person-uuid"); person.setLedgerId(1L); person.setName("Test Person"); person.setAvatar("");
        return person;
    }

    public static PersonUpdateReq personUpdateReq() {
        PersonUpdateReq req = new PersonUpdateReq(); req.setVersion(1); req.setName("Updated Person"); req.setAvatar(""); return req;
    }

    public static LedgerTransaction ledgerTransaction() {
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setUuid("transaction-uuid"); transaction.setLedgerId(1L); transaction.setType(0);
        transaction.setAmount(BigDecimal.ONE); transaction.setCurrencyCode("CNY"); transaction.setCategory("other");
        transaction.setCreatedByUserId(1L); transaction.setHappenedAt(LocalDateTime.now());
        return transaction;
    }
}

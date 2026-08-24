package com.simon.ledger.concurrency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.Result;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.LedgerMemberSummaryResp;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.dto.resp.ProfileConflictSnapshotResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictResponseContractTests {

    private static final String CONFLICT_MESSAGE = "数据已被其他设备修改";
    private static final Set<String> INTERNAL_OR_SENSITIVE_FIELDS = Set.of(
            "id", "passwordHash", "token", "deletedAt");

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void profileConflictSerializesSafeRealSnapshot() throws Exception {
        ProfileConflictSnapshotResp snapshot =
                new ProfileConflictSnapshotResp("user-uuid", "远端昵称", "avatar.png", 4);

        JsonNode root = conflictJson("profile", "user-uuid", 3, 4, false, snapshot);

        JsonNode remote = assertCommonContract(root, "profile", "user-uuid", 3, 4, false);
        assertExactFields(remote, "uuid", "nickname", "avatar", "version");
        assertText(remote, "uuid", "user-uuid");
        assertText(remote, "nickname", "远端昵称");
        assertText(remote, "avatar", "avatar.png");
        assertInteger(remote, "version", 4);
        assertNoInternalOrSensitiveFields(root);
    }

    @Test
    void ledgerConflictSerializesMembersAndVersions() throws Exception {
        LedgerMemberSummaryResp member = new LedgerMemberSummaryResp();
        member.setUuid("member-uuid");
        member.setUserUuid("member-user-uuid");
        member.setNickname("成员昵称");
        member.setAvatar("member.png");
        member.setRole("editor");
        member.setVersion(6);

        LedgerResp snapshot = new LedgerResp();
        snapshot.setUuid("ledger-uuid");
        snapshot.setName("远端账本");
        snapshot.setBaseCurrencyCode("CNY");
        snapshot.setExchangeRateToCny(new BigDecimal("1.00000000"));
        snapshot.setVersion(4);
        snapshot.setRole("owner");
        snapshot.setMemberCount(1);
        snapshot.setMembers(List.of(member));
        snapshot.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        snapshot.setUpdatedAt(LocalDateTime.of(2026, 8, 24, 10, 0));

        JsonNode root = conflictJson("ledger", "ledger-uuid", 3, 4, false, snapshot);

        JsonNode remote = assertCommonContract(root, "ledger", "ledger-uuid", 3, 4, false);
        assertExactFields(remote, "uuid", "name", "baseCurrencyCode", "exchangeRateToCny", "version",
                "role", "memberCount", "members", "createdAt", "updatedAt");
        assertInteger(remote, "version", 4);
        assertTrue(remote.path("exchangeRateToCny").isNumber());
        assertInteger(remote, "memberCount", 1);
        assertTrue(remote.path("members").isArray());
        JsonNode serializedMember = remote.path("members").get(0);
        assertExactFields(serializedMember, "uuid", "userUuid", "nickname", "avatar", "role", "version");
        assertText(serializedMember, "userUuid", "member-user-uuid");
        assertInteger(serializedMember, "version", 6);
        assertNoInternalOrSensitiveFields(root);
    }

    @Test
    void memberConflictSerializesPublicUserAndVersionFields() throws Exception {
        MemberResp snapshot = new MemberResp();
        snapshot.setUuid("member-uuid");
        snapshot.setUserUuid("member-user-uuid");
        snapshot.setNickname("成员昵称");
        snapshot.setAvatar("member.png");
        snapshot.setRole("admin");
        snapshot.setStatus(1);
        snapshot.setVersion(4);
        snapshot.setJoinedAt(LocalDateTime.of(2026, 8, 24, 9, 0));

        JsonNode root = conflictJson("member", "member-uuid", 3, 4, false, snapshot);

        JsonNode remote = assertCommonContract(root, "member", "member-uuid", 3, 4, false);
        assertExactFields(remote, "uuid", "userUuid", "nickname", "avatar", "role", "status", "version",
                "joinedAt");
        assertText(remote, "userUuid", "member-user-uuid");
        assertText(remote, "role", "admin");
        assertInteger(remote, "status", 1);
        assertInteger(remote, "version", 4);
        assertNoInternalOrSensitiveFields(root);
    }

    @Test
    void personConflictSerializesLinkedUserAndVersionFields() throws Exception {
        PersonResp snapshot = new PersonResp();
        snapshot.setUuid("person-uuid");
        snapshot.setLedgerUuid("ledger-uuid");
        snapshot.setLinkedUserUuid("linked-user-uuid");
        snapshot.setName("远端参与人");
        snapshot.setAvatar("person.png");
        snapshot.setVersion(4);
        snapshot.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        snapshot.setUpdatedAt(LocalDateTime.of(2026, 8, 24, 10, 0));

        JsonNode root = conflictJson("person", "person-uuid", 3, 4, true, snapshot);

        JsonNode remote = assertCommonContract(root, "person", "person-uuid", 3, 4, true);
        assertExactFields(remote, "uuid", "ledgerUuid", "linkedUserUuid", "name", "avatar", "version",
                "createdAt", "updatedAt");
        assertText(remote, "ledgerUuid", "ledger-uuid");
        assertText(remote, "linkedUserUuid", "linked-user-uuid");
        assertInteger(remote, "version", 4);
        assertNoInternalOrSensitiveFields(root);
    }

    @Test
    void transactionConflictSerializesRelationsAndUserDisplayFields() throws Exception {
        TransactionResp snapshot = new TransactionResp();
        snapshot.setUuid("transaction-uuid");
        snapshot.setLedgerUuid("ledger-uuid");
        snapshot.setType(0);
        snapshot.setPayerPersonUuid("payer-person-uuid");
        snapshot.setAmount(new BigDecimal("88.50"));
        snapshot.setCurrencyCode("CNY");
        snapshot.setCategory("餐饮");
        snapshot.setNote("远端备注");
        snapshot.setCreatedByUserUuid("creator-user-uuid");
        snapshot.setCreatedByNickname("创建者");
        snapshot.setCreatedByAvatar("creator.png");
        snapshot.setLastModifiedByUserUuid("modifier-user-uuid");
        snapshot.setLastModifiedByNickname("修改者");
        snapshot.setLastModifiedByAvatar("modifier.png");
        snapshot.setClientOperationId("client-operation-uuid");
        snapshot.setVersion(4);
        snapshot.setHappenedAt(LocalDateTime.of(2026, 8, 24, 8, 0));
        snapshot.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        snapshot.setUpdatedAt(LocalDateTime.of(2026, 8, 24, 10, 0));
        snapshot.setPersonUuids(List.of("person-uuid-1", "person-uuid-2"));

        JsonNode root = conflictJson("transaction", "transaction-uuid", 3, 4, false, snapshot);

        JsonNode remote = assertCommonContract(root, "transaction", "transaction-uuid", 3, 4, false);
        assertExactFields(remote, "uuid", "ledgerUuid", "type", "payerPersonUuid", "amount", "currencyCode",
                "category", "note", "createdByUserUuid", "createdByNickname", "createdByAvatar",
                "lastModifiedByUserUuid", "lastModifiedByNickname", "lastModifiedByAvatar", "clientOperationId",
                "version", "happenedAt", "createdAt", "updatedAt", "personUuids");
        assertText(remote, "payerPersonUuid", "payer-person-uuid");
        assertText(remote, "createdByUserUuid", "creator-user-uuid");
        assertText(remote, "createdByNickname", "创建者");
        assertText(remote, "createdByAvatar", "creator.png");
        assertText(remote, "lastModifiedByUserUuid", "modifier-user-uuid");
        assertText(remote, "lastModifiedByNickname", "修改者");
        assertText(remote, "lastModifiedByAvatar", "modifier.png");
        assertInteger(remote, "version", 4);
        assertTrue(remote.path("personUuids").isArray());
        assertEquals(List.of("person-uuid-1", "person-uuid-2"),
                objectMapper.convertValue(remote.path("personUuids"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertNoInternalOrSensitiveFields(root);
    }

    @Test
    void successfulVersionMutationSerializesNewVersionContract() throws Exception {
        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(
                Result.ok(new VersionMutationResp("transaction-uuid", 5, true))));

        assertExactFields(root, "code", "message", "data");
        assertInteger(root, "code", 0);
        assertText(root, "message", "ok");
        JsonNode data = root.path("data");
        assertExactFields(data, "uuid", "version", "deleted");
        assertText(data, "uuid", "transaction-uuid");
        assertInteger(data, "version", 5);
        assertTrue(data.path("deleted").isBoolean());
        assertTrue(data.path("deleted").asBoolean());
        assertNoInternalOrSensitiveFields(root);
    }

    private JsonNode conflictJson(
            String entityType,
            String entityUuid,
            int submittedVersion,
            int remoteVersion,
            boolean remoteDeleted,
            Object remoteSnapshot) throws Exception {
        ConflictResp conflict = new ConflictResp(
                entityType, entityUuid, submittedVersion, remoteVersion, remoteDeleted, remoteSnapshot);
        Result<ConflictResp> result = Result.fail(ErrorCode.CONFLICT, CONFLICT_MESSAGE, conflict);
        return objectMapper.readTree(objectMapper.writeValueAsString(result));
    }

    private JsonNode assertCommonContract(
            JsonNode root,
            String entityType,
            String entityUuid,
            int submittedVersion,
            int remoteVersion,
            boolean remoteDeleted) {
        assertExactFields(root, "code", "message", "data");
        assertInteger(root, "code", 409001);
        assertText(root, "message", CONFLICT_MESSAGE);

        JsonNode data = root.path("data");
        assertTrue(data.isObject());
        assertExactFields(data, "entityType", "entityUuid", "submittedVersion", "remoteVersion",
                "remoteDeleted", "remoteSnapshot");
        assertText(data, "entityType", entityType);
        assertText(data, "entityUuid", entityUuid);
        assertInteger(data, "submittedVersion", submittedVersion);
        assertInteger(data, "remoteVersion", remoteVersion);
        assertTrue(data.path("remoteDeleted").isBoolean());
        assertEquals(remoteDeleted, data.path("remoteDeleted").asBoolean());

        JsonNode remoteSnapshot = data.path("remoteSnapshot");
        assertTrue(remoteSnapshot.isObject());
        assertFalse(remoteSnapshot.isEmpty());
        return remoteSnapshot;
    }

    private void assertExactFields(JsonNode node, String... expected) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of(expected), names);
    }

    private void assertText(JsonNode node, String field, String expected) {
        assertTrue(node.path(field).isTextual(), field);
        assertEquals(expected, node.path(field).asText(), field);
    }

    private void assertInteger(JsonNode node, String field, int expected) {
        assertTrue(node.path(field).isIntegralNumber(), field);
        assertEquals(expected, node.path(field).asInt(), field);
    }

    private void assertNoInternalOrSensitiveFields(JsonNode root) {
        List<String> found = new ArrayList<>();
        collectForbiddenFields(root, "$", found);
        assertTrue(found.isEmpty(), "internal/sensitive JSON fields: " + found);
    }

    private void collectForbiddenFields(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode value = node.path(name);
                boolean numericInternalId = value.isIntegralNumber()
                        && (name.equals("id") || name.endsWith("Id"));
                if (INTERNAL_OR_SENSITIVE_FIELDS.contains(name) || numericInternalId) {
                    found.add(path + "." + name);
                }
                collectForbiddenFields(value, path + "." + name, found);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectForbiddenFields(node.get(i), path + "[" + i + "]", found);
            }
        }
    }
}

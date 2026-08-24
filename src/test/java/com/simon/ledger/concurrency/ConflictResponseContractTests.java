package com.simon.ledger.concurrency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.Result;
import com.simon.ledger.SimonLedgerApiApplication;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.LedgerMemberSummaryResp;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.dto.resp.ProfileConflictSnapshotResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@JsonTest
@Import(ConflictResponseContractTests.NoDatabaseMyBatisTestConfiguration.class)
class ConflictResponseContractTests {

    private static final String CONFLICT_MESSAGE = "数据已被其他设备修改";
    private static final Set<String> INTERNAL_OR_SENSITIVE_FIELDS = Set.of(
            "passwordHash", "token", "deletedAt");
    private static final Set<String> PUBLIC_ID_FIELDS = Set.of("clientOperationId");

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @TestConfiguration(proxyBeanMethods = false)
    static class NoDatabaseMyBatisTestConfiguration {

        @Bean
        SqlSessionFactory sqlSessionFactory() {
            Environment environment = new Environment(
                    "json-contract-test",
                    new JdbcTransactionFactory(),
                    mock(DataSource.class));
            return new DefaultSqlSessionFactory(new org.apache.ibatis.session.Configuration(environment));
        }
    }

    @Test
    void usesBootManagedObjectMapperFromJsonSlice() throws Exception {
        assertTrue(getClass().isAnnotationPresent(JsonTest.class));
        assertNotNull(getClass().getDeclaredField("objectMapper").getAnnotation(Autowired.class));
        assertSame(applicationContext.getBean(ObjectMapper.class), objectMapper);
        assertFalse(objectMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertTrue(applicationContext.getBeansOfType(DataSource.class).isEmpty());
    }

    @Test
    void jsonSliceUsesProductionApplicationAsItsConfigurationSource() {
        assertFalse(applicationContext.getBeansOfType(SimonLedgerApiApplication.class).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("unsafeInternalIdFields")
    void recursiveSafetyRejectsInternalIdFieldsRegardlessOfValueType(
            String json,
            String expectedPath) throws Exception {
        assertEquals(List.of(expectedPath), internalOrSensitiveFields(objectMapper.readTree(json)));
    }

    @Test
    void recursiveSafetyAllowsPublicUuidAndClientOperationIdFields() throws Exception {
        JsonNode safe = objectMapper.readTree("""
                {
                  "entityUuid": "entity-uuid",
                  "userUuid": "user-uuid",
                  "payerPersonUuid": null,
                  "nested": {"linkedUserUuid": "linked-user-uuid"},
                  "clientOperationId": "operation-uuid"
                }
                """);

        assertTrue(internalOrSensitiveFields(safe).isEmpty());
    }

    private static Stream<Arguments> unsafeInternalIdFields() {
        return Stream.of(
                Arguments.of("{\"id\":null}", "$.id"),
                Arguments.of("{\"userId\":\"7\"}", "$.userId"),
                Arguments.of("{\"nested\":{\"ownerUserId\":null}}", "$.nested.ownerUserId"),
                Arguments.of("{\"items\":[{\"personId\":{\"value\":1}}]}", "$.items[0].personId"));
    }

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
        assertText(remote, "uuid", "ledger-uuid");
        assertText(remote, "name", "远端账本");
        assertText(remote, "baseCurrencyCode", "CNY");
        assertNumber(remote, "exchangeRateToCny", new BigDecimal("1.00000000"));
        assertInteger(remote, "version", 4);
        assertText(remote, "role", "owner");
        assertInteger(remote, "memberCount", 1);
        assertTrue(remote.path("members").isArray());
        assertEquals(1, remote.path("members").size());
        assertIsoDateTime(remote, "createdAt", LocalDateTime.of(2026, 8, 24, 9, 0));
        assertIsoDateTime(remote, "updatedAt", LocalDateTime.of(2026, 8, 24, 10, 0));
        JsonNode serializedMember = remote.path("members").get(0);
        assertExactFields(serializedMember, "uuid", "userUuid", "nickname", "avatar", "role", "version");
        assertText(serializedMember, "uuid", "member-uuid");
        assertText(serializedMember, "userUuid", "member-user-uuid");
        assertText(serializedMember, "nickname", "成员昵称");
        assertText(serializedMember, "avatar", "member.png");
        assertText(serializedMember, "role", "editor");
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
        assertText(remote, "uuid", "member-uuid");
        assertText(remote, "userUuid", "member-user-uuid");
        assertText(remote, "nickname", "成员昵称");
        assertText(remote, "avatar", "member.png");
        assertText(remote, "role", "admin");
        assertInteger(remote, "status", 1);
        assertInteger(remote, "version", 4);
        assertIsoDateTime(remote, "joinedAt", LocalDateTime.of(2026, 8, 24, 9, 0));
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
        assertText(remote, "uuid", "person-uuid");
        assertText(remote, "ledgerUuid", "ledger-uuid");
        assertText(remote, "linkedUserUuid", "linked-user-uuid");
        assertText(remote, "name", "远端参与人");
        assertText(remote, "avatar", "person.png");
        assertInteger(remote, "version", 4);
        assertIsoDateTime(remote, "createdAt", LocalDateTime.of(2026, 8, 24, 9, 0));
        assertIsoDateTime(remote, "updatedAt", LocalDateTime.of(2026, 8, 24, 10, 0));
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
        assertText(remote, "uuid", "transaction-uuid");
        assertText(remote, "ledgerUuid", "ledger-uuid");
        assertInteger(remote, "type", 0);
        assertText(remote, "payerPersonUuid", "payer-person-uuid");
        assertNumber(remote, "amount", new BigDecimal("88.50"));
        assertText(remote, "currencyCode", "CNY");
        assertText(remote, "category", "餐饮");
        assertText(remote, "note", "远端备注");
        assertText(remote, "createdByUserUuid", "creator-user-uuid");
        assertText(remote, "createdByNickname", "创建者");
        assertText(remote, "createdByAvatar", "creator.png");
        assertText(remote, "lastModifiedByUserUuid", "modifier-user-uuid");
        assertText(remote, "lastModifiedByNickname", "修改者");
        assertText(remote, "lastModifiedByAvatar", "modifier.png");
        assertText(remote, "clientOperationId", "client-operation-uuid");
        assertInteger(remote, "version", 4);
        assertIsoDateTime(remote, "happenedAt", LocalDateTime.of(2026, 8, 24, 8, 0));
        assertIsoDateTime(remote, "createdAt", LocalDateTime.of(2026, 8, 24, 9, 0));
        assertIsoDateTime(remote, "updatedAt", LocalDateTime.of(2026, 8, 24, 10, 0));
        assertTrue(remote.path("personUuids").isArray());
        assertTrue(remote.path("personUuids").get(0).isTextual());
        assertTrue(remote.path("personUuids").get(1).isTextual());
        assertEquals(List.of("person-uuid-1", "person-uuid-2"),
                objectMapper.convertValue(remote.path("personUuids"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertNoInternalOrSensitiveFields(root);
    }

    @Test
    void nullablePublicSnapshotFieldsRemainExplicitJsonNull() throws Exception {
        ProfileConflictSnapshotResp profile =
                new ProfileConflictSnapshotResp("user-uuid", "远端昵称", null, 4);
        JsonNode profileRemote = assertCommonContract(
                conflictJson("profile", "user-uuid", 3, 4, false, profile),
                "profile", "user-uuid", 3, 4, false);
        assertNullNode(profileRemote, "avatar");

        PersonResp person = new PersonResp();
        person.setUuid("person-uuid");
        person.setLedgerUuid("ledger-uuid");
        person.setLinkedUserUuid(null);
        person.setName("手动参与人");
        person.setAvatar("");
        person.setVersion(4);
        person.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        person.setUpdatedAt(LocalDateTime.of(2026, 8, 24, 10, 0));
        JsonNode personRemote = assertCommonContract(
                conflictJson("person", "person-uuid", 3, 4, false, person),
                "person", "person-uuid", 3, 4, false);
        assertNullNode(personRemote, "linkedUserUuid");
        assertText(personRemote, "avatar", "");

        TransactionResp transaction = new TransactionResp();
        transaction.setUuid("transaction-uuid");
        transaction.setLedgerUuid("ledger-uuid");
        transaction.setType(1);
        transaction.setPayerPersonUuid(null);
        transaction.setAmount(new BigDecimal("25.00"));
        transaction.setCurrencyCode("CNY");
        transaction.setCategory("工资");
        transaction.setNote(null);
        transaction.setCreatedByUserUuid("creator-user-uuid");
        transaction.setCreatedByNickname("创建者");
        transaction.setCreatedByAvatar(null);
        transaction.setLastModifiedByUserUuid(null);
        transaction.setLastModifiedByNickname(null);
        transaction.setLastModifiedByAvatar(null);
        transaction.setClientOperationId(null);
        transaction.setVersion(4);
        transaction.setHappenedAt(LocalDateTime.of(2026, 8, 24, 8, 0));
        transaction.setCreatedAt(LocalDateTime.of(2026, 8, 24, 9, 0));
        transaction.setUpdatedAt(LocalDateTime.of(2026, 8, 24, 10, 0));
        transaction.setPersonUuids(List.of());
        JsonNode transactionRemote = assertCommonContract(
                conflictJson("transaction", "transaction-uuid", 3, 4, false, transaction),
                "transaction", "transaction-uuid", 3, 4, false);
        for (String field : List.of("payerPersonUuid", "note", "createdByAvatar", "lastModifiedByUserUuid",
                "lastModifiedByNickname", "lastModifiedByAvatar", "clientOperationId")) {
            assertNullNode(transactionRemote, field);
        }
        assertTrue(transactionRemote.path("personUuids").isArray());
        assertTrue(transactionRemote.path("personUuids").isEmpty());
        assertNoInternalOrSensitiveFields(profileRemote);
        assertNoInternalOrSensitiveFields(personRemote);
        assertNoInternalOrSensitiveFields(transactionRemote);
    }

    @Test
    void successfulVersionMutationSerializesNewVersionContract() throws Exception {
        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(
                Result.ok(new VersionMutationResp("transaction-uuid", 5, true))));

        assertExactFields(root, "code", "message", "data");
        assertInteger(root, "code", 0);
        assertText(root, "message", "ok");
        JsonNode data = root.path("data");
        assertTrue(data.isObject());
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

    private void assertNumber(JsonNode node, String field, BigDecimal expected) {
        assertTrue(node.path(field).isNumber(), field);
        assertEquals(0, expected.compareTo(node.path(field).decimalValue()), field);
    }

    private void assertIsoDateTime(JsonNode node, String field, LocalDateTime expected) {
        assertText(node, field, DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(expected));
    }

    private void assertNullNode(JsonNode node, String field) {
        assertTrue(node.has(field), field);
        assertTrue(node.get(field).isNull(), field);
    }

    private void assertNoInternalOrSensitiveFields(JsonNode root) {
        List<String> found = internalOrSensitiveFields(root);
        assertTrue(found.isEmpty(), "internal/sensitive JSON fields: " + found);
    }

    private List<String> internalOrSensitiveFields(JsonNode root) {
        List<String> found = new ArrayList<>();
        collectForbiddenFields(root, "$", found);
        return found;
    }

    private void collectForbiddenFields(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode value = node.path(name);
                boolean internalIdField = (name.equals("id") || name.endsWith("Id"))
                        && !PUBLIC_ID_FIELDS.contains(name);
                if (INTERNAL_OR_SENSITIVE_FIELDS.contains(name) || internalIdField) {
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

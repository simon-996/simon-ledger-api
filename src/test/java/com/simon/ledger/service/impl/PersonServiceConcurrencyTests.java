package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.controller.PersonController;
import com.simon.ledger.dto.req.PersonUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.PersonResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.PersonService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PersonServiceConcurrencyTests {

    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper memberMapper;
    @Mock private LedgerPersonMapper personMapper;
    @Mock private UserAccountMapper userMapper;
    @Mock private ChangeLogService changeLogService;

    private PersonServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(Ledger.class);
        initializeLambdaMetadata(LedgerMember.class);
        initializeLambdaMetadata(LedgerPerson.class);
        initializeLambdaMetadata(UserAccount.class);
        service = new PersonServiceImpl(ledgerMapper, memberMapper, userMapper, changeLogService);
        ReflectionTestUtils.setField(service, "baseMapper", personMapper);
    }

    @Test
    void ownerUpdatesWithAtomicVersionPredicateAndReturnsSafeIncrementedResponse() {
        LedgerPerson target = person(22L, "person-uuid", 8L, "Old", 4, null);
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(personMapper.selectOne(any())).thenReturn(target, (LedgerPerson) null);
        when(userMapper.selectOne(any())).thenReturn(user(8L, "linked-user"));
        when(personMapper.update(isNull(), any())).thenReturn(1);

        PersonResp response = loggedIn(() -> service.update("ledger-uuid", "person-uuid",
                updateReq(4, " New Name ", " new.png ", "linked-user")));

        assertPersonResponse(response, "linked-user", "New Name", "new.png", 5);
        ArgumentCaptor<LambdaUpdateWrapper<LedgerPerson>> update = personUpdateCaptor();
        verify(personMapper).update(isNull(), update.capture());
        assertAtomicWrapper(update.getValue(), "deleted_at IS NULL", 4, 5);
        assertSetColumns(update.getValue(), "linked_user_id", "name", "avatar", "version", "updated_at");
        assertOperatorReadLockedAndScoped();
        verify(personMapper, never()).updateById(any(LedgerPerson.class));
        verify(changeLogService).record(11L, "person", "person-uuid", "update", 7L);
    }

    @Test
    void adminDeletesWithAtomicVersionPredicateAndReturnsTombstone() {
        LedgerPerson target = person(22L, "person-uuid", null, "Manual", 4, null);
        stubLedgerAndOperator(LedgerRoles.ADMIN);
        when(personMapper.selectOne(any())).thenReturn(target);
        when(personMapper.update(isNull(), any())).thenReturn(1);

        VersionMutationResp response = loggedIn(() -> invokeDelete("ledger-uuid", "person-uuid", deleteReq(4)));

        assertEquals("person-uuid", response.getUuid());
        assertEquals(5, response.getVersion());
        assertEquals(true, response.getDeleted());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerPerson>> update = personUpdateCaptor();
        verify(personMapper).update(isNull(), update.capture());
        assertAtomicWrapper(update.getValue(), "deleted_at IS NULL", 4, 5);
        assertSetColumns(update.getValue(), "deleted_at", "updated_at", "version");
        assertOperatorReadLockedAndScoped();
        verify(personMapper, never()).updateById(any(LedgerPerson.class));
        verify(changeLogService).record(11L, "person", "person-uuid", "delete", 7L);
    }

    @Test
    void ownerRestoresLinkedPersonAndReturnsMappedIncrementedResponse() {
        LedgerPerson target = person(22L, "person-uuid", 8L, "Old", 5, LocalDateTime.now());
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(personMapper.selectOne(any())).thenReturn(target, (LedgerPerson) null);
        when(userMapper.selectOne(any())).thenReturn(user(9L, "new-linked-user"));
        when(personMapper.update(isNull(), any())).thenReturn(1);

        PersonResp response = loggedIn(() -> invokeRestore("ledger-uuid", "person-uuid",
                updateReq(5, " Restored ", " restored.png ", "new-linked-user")));

        assertPersonResponse(response, "new-linked-user", "Restored", "restored.png", 6);
        ArgumentCaptor<LambdaUpdateWrapper<LedgerPerson>> update = personUpdateCaptor();
        verify(personMapper).update(isNull(), update.capture());
        assertAtomicWrapper(update.getValue(), "deleted_at IS NOT NULL", 5, 6);
        assertSetColumns(update.getValue(), "linked_user_id", "name", "avatar", "deleted_at", "updated_at", "version");
        assertOperatorReadLockedAndScoped();
        verify(changeLogService).record(11L, "person", "person-uuid", "update", 7L);
    }

    @Test
    void editorAndViewerCannotPerformAnyPersonMutation() {
        for (String role : List.of(LedgerRoles.EDITOR, LedgerRoles.VIEWER)) {
            for (String operation : List.of("update", "delete", "restore")) {
                reset(ledgerMapper, memberMapper, personMapper, userMapper, changeLogService);
                stubLedgerAndOperator(role);

                BusinessException exception = assertThrows(BusinessException.class, () -> loggedIn(() -> {
                    if ("update".equals(operation)) {
                        service.update("ledger-uuid", "person-uuid", updateReq(4, "Name", "", null));
                    } else if ("delete".equals(operation)) {
                        invokeDelete("ledger-uuid", "person-uuid", deleteReq(4));
                    } else {
                        invokeRestore("ledger-uuid", "person-uuid", updateReq(4, "Name", "", null));
                    }
                    return null;
                }));

                assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
                assertOperatorReadLockedAndScoped();
                verify(personMapper, never()).selectOne(any());
                verify(personMapper, never()).update(isNull(), any());
                verify(changeLogService, never()).record(any(), any(), any(), any(), any());
            }
        }
    }

    @Test
    void inactiveOperatorIsForbiddenBeforeTargetRead() {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", "person-uuid",
                        updateReq(4, "Name", "", null))));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(personMapper, never()).selectOne(any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void staleUpdateConflictsBeforeLinkedUserLookupOrBindingValidation() {
        LedgerPerson target = person(22L, "person-uuid", null, "Remote", 5, null);
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(personMapper.selectOne(any())).thenReturn(target);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", "person-uuid",
                        updateReq(4, "Requested", "", "missing-linked-user"))));

        assertPersonConflict(exception, 4, 5, false, null, "Remote");
        verify(userMapper, never()).selectOne(any());
        verify(personMapper, times(1)).selectOne(any());
        verify(personMapper, never()).update(isNull(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void staleRestoreConflictsBeforeManualNameValidation() {
        LedgerPerson target = person(22L, "person-uuid", null, "Remote", 6, LocalDateTime.now());
        stubLedgerAndOperator(LedgerRoles.ADMIN);
        when(personMapper.selectOne(any())).thenReturn(target);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "person-uuid",
                        updateReq(5, "Duplicate", "", null))));

        assertPersonConflict(exception, 5, 6, true, null, "Remote");
        verify(personMapper, times(1)).selectOne(any());
        verify(personMapper, never()).update(isNull(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateAndDeleteOfRemoteDeletedPersonConflictAndActiveRestoreConflicts() {
        for (String operation : List.of("update", "delete", "restore")) {
            reset(ledgerMapper, memberMapper, personMapper, userMapper, changeLogService);
            boolean restore = "restore".equals(operation);
            LedgerPerson target = person(22L, "person-uuid", null, "Remote", 4,
                    restore ? null : LocalDateTime.now());
            stubLedgerAndOperator(LedgerRoles.OWNER);
            when(personMapper.selectOne(any())).thenReturn(target);

            VersionConflictException exception = assertThrows(VersionConflictException.class, () -> loggedIn(() -> {
                if ("update".equals(operation)) {
                    service.update("ledger-uuid", "person-uuid", updateReq(4, "Name", "", null));
                } else if ("delete".equals(operation)) {
                    invokeDelete("ledger-uuid", "person-uuid", deleteReq(4));
                } else {
                    invokeRestore("ledger-uuid", "person-uuid", updateReq(4, "Name", "", null));
                }
                return null;
            }));

            assertPersonConflict(exception, 4, 4, !restore, null, "Remote");
            verify(personMapper, never()).update(isNull(), any());
            verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        }
    }

    @Test
    void missingOrWrongLedgerTargetIsNotFoundAndLookupIncludesDeletedRows() {
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(personMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", "other-ledger-person",
                        updateReq(4, "Name", "", null))));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        ArgumentCaptor<Wrapper<LedgerPerson>> targetRead = personWrapperCaptor();
        verify(personMapper).selectOne(targetRead.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) targetRead.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.matches("(?s).*\\bledger_id\\b\\s*=.*"));
        assertTrue(sql.matches("(?s).*\\buuid\\b\\s*=.*"));
        assertTrue(!sql.contains("deleted_at"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(11L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("other-ledger-person"));
    }

    @Test
    void matchingVersionThenEnforcesLinkedUserBindingUniqueness() {
        LedgerPerson target = person(22L, "person-uuid", null, "Manual", 4, null);
        LedgerPerson duplicate = person(23L, "duplicate", 9L, "Existing", 2, null);
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(personMapper.selectOne(any())).thenReturn(target, duplicate);
        when(userMapper.selectOne(any())).thenReturn(user(9L, "linked-user"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", "person-uuid",
                        updateReq(4, "Requested", "", "linked-user"))));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verify(userMapper).selectOne(any());
        ArgumentCaptor<Wrapper<LedgerPerson>> reads = personWrapperCaptor();
        verify(personMapper, times(2)).selectOne(reads.capture());
        assertUniquenessReadExcludesCurrentPerson(reads.getAllValues().get(1));
        verify(personMapper, never()).update(isNull(), any());
    }

    @Test
    void matchingVersionThenEnforcesManualNameUniquenessOnRestore() {
        LedgerPerson target = person(22L, "person-uuid", 8L, "Deleted", 4, LocalDateTime.now());
        LedgerPerson duplicate = person(23L, "duplicate", null, "Duplicate", 2, null);
        stubLedgerAndOperator(LedgerRoles.ADMIN);
        when(personMapper.selectOne(any())).thenReturn(target, duplicate);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "person-uuid",
                        updateReq(4, " Duplicate ", "", null))));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        ArgumentCaptor<Wrapper<LedgerPerson>> reads = personWrapperCaptor();
        verify(personMapper, times(2)).selectOne(reads.capture());
        assertUniquenessReadExcludesCurrentPerson(reads.getAllValues().get(1));
        verify(personMapper, never()).update(isNull(), any());
    }

    @Test
    void affectedZeroReloadsLatestLinkedPersonForUpdateAndReturnsSafeConflictWithoutLog() {
        LedgerPerson initial = person(22L, "person-uuid", null, "Initial", 4, null);
        LedgerPerson latest = person(22L, "person-uuid", 9L, "Profile Updated", 5, null);
        latest.setAvatar("latest.png");
        latest.setUpdatedAt(LocalDateTime.of(2026, 2, 2, 0, 0));
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(personMapper.selectOne(any())).thenReturn(initial, null, latest);
        when(personMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "latest-linked-user"));

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", "person-uuid",
                        updateReq(4, "Requested", "", null))));

        assertPersonConflict(exception, 4, 5, false, "latest-linked-user", "Profile Updated");
        ArgumentCaptor<Wrapper<LedgerPerson>> reads = personWrapperCaptor();
        verify(personMapper, times(3)).selectOne(reads.capture());
        AbstractWrapper<?, ?, ?> reload = (AbstractWrapper<?, ?, ?>) reads.getAllValues().get(2);
        assertTrue(reload.getSqlSegment().contains("FOR UPDATE"));
        assertTrue(reload.getSqlSegment().matches("(?s).*\\bid\\b\\s*=.*"));
        assertTrue(reload.getParamNameValuePairs().containsValue(22L));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deleteAndRestoreAffectedZeroReloadLatestTargetAndNeverLog() {
        for (String operation : List.of("delete", "restore")) {
            reset(ledgerMapper, memberMapper, personMapper, userMapper, changeLogService);
            boolean restore = "restore".equals(operation);
            LedgerPerson initial = person(22L, "person-uuid", null, "Initial", 4,
                    restore ? LocalDateTime.now() : null);
            LedgerPerson latest = person(22L, "person-uuid", null, "Latest", 5,
                    restore ? null : LocalDateTime.now());
            stubLedgerAndOperator(LedgerRoles.OWNER);
            when(personMapper.selectOne(any())).thenReturn(initial, restore ? null : latest, latest);
            when(personMapper.update(isNull(), any())).thenReturn(0);

            VersionConflictException exception = assertThrows(VersionConflictException.class, () -> loggedIn(() -> {
                if (restore) {
                    invokeRestore("ledger-uuid", "person-uuid", updateReq(4, "Restored", "", null));
                } else {
                    invokeDelete("ledger-uuid", "person-uuid", deleteReq(4));
                }
                return null;
            }));

            assertEquals(5, ((ConflictResp) exception.getData()).getRemoteVersion());
            ArgumentCaptor<Wrapper<LedgerPerson>> reads = personWrapperCaptor();
            int expectedReads = restore ? 3 : 2;
            verify(personMapper, times(expectedReads)).selectOne(reads.capture());
            assertTrue(reads.getAllValues().get(expectedReads - 1).getSqlSegment().contains("FOR UPDATE"));
            verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        }
    }

    @Test
    void controllerUsesValidatedBodiesExactPathsAndTypedIdempotencyForAllMutations() throws Exception {
        PersonResp personResponse = new PersonResp();
        personResponse.setUuid("person-uuid");
        personResponse.setVersion(5);
        VersionMutationResp deleted = new VersionMutationResp("person-uuid", 5, true);
        PersonService personService = (PersonService) Proxy.newProxyInstance(
                PersonService.class.getClassLoader(), new Class<?>[]{PersonService.class}, (proxy, method, args) -> {
                    if ("delete".equals(method.getName())) {
                        return deleted;
                    }
                    if ("update".equals(method.getName()) || "restore".equals(method.getName())) {
                        return personResponse;
                    }
                    return null;
                });
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(4);
            return supplier.get();
        });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new PersonController(personService, idempotencyService)).build();

        mvc.perform(put("/api/ledgers/ledger-uuid/people/person-uuid")
                        .header("Idempotency-Key", "update-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"name\":\"Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("update-key"), eq("PUT"),
                eq("/api/ledgers/ledger-uuid/people/person-uuid"), eq(PersonResp.class), any());

        mvc.perform(delete("/api/ledgers/ledger-uuid/people/person-uuid")
                        .header("Idempotency-Key", "delete-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("person-uuid"))
                .andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("delete-key"), eq("DELETE"),
                eq("/api/ledgers/ledger-uuid/people/person-uuid"), eq(VersionMutationResp.class), any());
        verify(idempotencyService, never()).executeVoid(any(), any(), any(), any());
        mvc.perform(delete("/api/ledgers/ledger-uuid/people/person-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/ledgers/ledger-uuid/people/person-uuid/restore")
                        .header("Idempotency-Key", "restore-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"name\":\"Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("restore-key"), eq("POST"),
                eq("/api/ledgers/ledger-uuid/people/person-uuid/restore"), eq(PersonResp.class), any());
    }

    @Test
    void writeMethodsAreTransactionalAndExposeVersionedSignatures() throws Exception {
        assertTransactional("update", PersonUpdateReq.class);
        assertTransactional("delete", VersionDeleteReq.class);
        assertTransactional("restore", PersonUpdateReq.class);
        assertEquals(PersonResp.class, PersonService.class
                .getMethod("update", String.class, String.class, PersonUpdateReq.class).getReturnType());
        assertEquals(VersionMutationResp.class, PersonService.class
                .getMethod("delete", String.class, String.class, VersionDeleteReq.class).getReturnType());
        assertEquals(PersonResp.class, PersonService.class
                .getMethod("restore", String.class, String.class, PersonUpdateReq.class).getReturnType());
    }

    private void assertTransactional(String name, Class<?> requestType) throws Exception {
        Method method = PersonServiceImpl.class.getMethod(name, String.class, String.class, requestType);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertEquals(Set.of(Exception.class), Set.of(annotation.rollbackFor()));
    }

    private void assertAtomicWrapper(LambdaUpdateWrapper<?> wrapper, String deletedPredicate,
                                     int submittedVersion, int nextVersion) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.matches("(?s).*\\bid\\b\\s*=.*"));
        assertTrue(sql.matches("(?s).*\\bversion\\b\\s*=.*"));
        assertTrue(sql.contains(deletedPredicate));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(22L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(submittedVersion));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(nextVersion));
    }

    private void assertSetColumns(LambdaUpdateWrapper<?> wrapper, String... columns) {
        for (String column : columns) {
            assertTrue(wrapper.getSqlSet().matches("(?s).*\\b" + column + "\\b.*"),
                    () -> "missing SET column " + column + " in " + wrapper.getSqlSet());
        }
    }

    private void assertOperatorReadLockedAndScoped() {
        ArgumentCaptor<Wrapper<LedgerMember>> operatorRead = memberWrapperCaptor();
        verify(memberMapper).selectOne(operatorRead.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) operatorRead.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.matches("(?s).*\\bledger_id\\b\\s*=.*"));
        assertTrue(sql.matches("(?s).*\\buser_id\\b\\s*=.*"));
        assertTrue(sql.matches("(?s).*\\bstatus\\b\\s*=.*"));
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertTrue(sql.contains("FOR UPDATE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(11L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1));
    }

    private void assertUniquenessReadExcludesCurrentPerson(Wrapper<LedgerPerson> read) {
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read;
        assertTrue(wrapper.getSqlSegment().matches("(?s).*\\bid\\b\\s*<>.*"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(22L));
    }

    private void assertPersonResponse(PersonResp response, String linkedUserUuid, String name,
                                      String avatar, int version) {
        assertEquals("person-uuid", response.getUuid());
        assertEquals("ledger-uuid", response.getLedgerUuid());
        assertEquals(linkedUserUuid, response.getLinkedUserUuid());
        assertEquals(name, response.getName());
        assertEquals(avatar, response.getAvatar());
        assertEquals(version, response.getVersion());
        assertNotNull(response.getCreatedAt());
        assertNotNull(response.getUpdatedAt());
    }

    private void assertPersonConflict(VersionConflictException exception, int submitted, int remote,
                                      boolean deleted, String linkedUserUuid, String name) {
        ConflictResp conflict = assertInstanceOf(ConflictResp.class, exception.getData());
        assertEquals("person", conflict.getEntityType());
        assertEquals("person-uuid", conflict.getEntityUuid());
        assertEquals(submitted, conflict.getSubmittedVersion());
        assertEquals(remote, conflict.getRemoteVersion());
        assertEquals(deleted, conflict.getRemoteDeleted());
        PersonResp snapshot = assertInstanceOf(PersonResp.class, conflict.getRemoteSnapshot());
        assertEquals("person-uuid", snapshot.getUuid());
        assertEquals("ledger-uuid", snapshot.getLedgerUuid());
        assertEquals(linkedUserUuid, snapshot.getLinkedUserUuid());
        assertEquals(name, snapshot.getName());
        assertEquals(remote, snapshot.getVersion());
        assertNotNull(snapshot.getCreatedAt());
        assertNotNull(snapshot.getUpdatedAt());
        assertEquals(Set.of("uuid", "ledgerUuid", "linkedUserUuid", "name", "avatar", "version",
                        "createdAt", "updatedAt"),
                java.util.Arrays.stream(PersonResp.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet()));
    }

    private void stubLedgerAndOperator(String role) {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(operator(role));
    }

    private Ledger ledger() {
        Ledger ledger = new Ledger();
        ledger.setId(11L);
        ledger.setUuid("ledger-uuid");
        return ledger;
    }

    private LedgerMember operator(String role) {
        LedgerMember member = new LedgerMember();
        member.setId(21L);
        member.setLedgerId(11L);
        member.setUserId(7L);
        member.setRole(role);
        member.setStatus(1);
        member.setVersion(3);
        return member;
    }

    private LedgerPerson person(Long id, String uuid, Long linkedUserId, String name,
                                int version, LocalDateTime deletedAt) {
        LedgerPerson person = new LedgerPerson();
        person.setId(id);
        person.setUuid(uuid);
        person.setLedgerId(11L);
        person.setLinkedUserId(linkedUserId);
        person.setName(name);
        person.setAvatar("avatar.png");
        person.setVersion(version);
        person.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        person.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        person.setDeletedAt(deletedAt);
        return person;
    }

    private UserAccount user(Long id, String uuid) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUuid(uuid);
        user.setNickname("Test User");
        user.setAvatar("user.png");
        return user;
    }

    private PersonUpdateReq updateReq(int version, String name, String avatar, String linkedUserUuid) {
        PersonUpdateReq req = new PersonUpdateReq();
        req.setVersion(version);
        req.setName(name);
        req.setAvatar(avatar);
        req.setLinkedUserUuid(linkedUserUuid);
        return req;
    }

    private VersionDeleteReq deleteReq(int version) {
        VersionDeleteReq req = new VersionDeleteReq();
        req.setVersion(version);
        return req;
    }

    private VersionMutationResp invokeDelete(String ledgerUuid, String personUuid, VersionDeleteReq req) {
        return invokeVersioned("delete", VersionDeleteReq.class, ledgerUuid, personUuid, req);
    }

    private PersonResp invokeRestore(String ledgerUuid, String personUuid, PersonUpdateReq req) {
        return invokeVersioned("restore", PersonUpdateReq.class, ledgerUuid, personUuid, req);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeVersioned(String methodName, Class<?> requestType,
                                  String ledgerUuid, String personUuid, Object req) {
        try {
            Method method = PersonServiceImpl.class.getMethod(methodName, String.class, String.class, requestType);
            return (T) method.invoke(service, ledgerUuid, personUuid, req);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Missing versioned person lifecycle method: " + methodName, exception);
        }
    }

    private <T> T loggedIn(ServiceCall<T> call) {
        try (MockedStatic<StpUtil> stpUtil = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            return call.call();
        }
    }

    private void initializeLambdaMetadata(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<LedgerPerson>> personUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LedgerPerson>> personWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LedgerMember>> memberWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @FunctionalInterface
    private interface ServiceCall<T> {
        T call();
    }
}

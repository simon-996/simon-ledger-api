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
import com.simon.ledger.controller.TransactionController;
import com.simon.ledger.dto.req.TransactionCreateReq;
import com.simon.ledger.dto.req.TransactionUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.TransactionResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.LedgerTransactionPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.LedgerTransactionMapper;
import com.simon.ledger.mapper.LedgerTransactionPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.TransactionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeast;
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
class TransactionServiceConcurrencyTests {

    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper memberMapper;
    @Mock private LedgerPersonMapper personMapper;
    @Mock private LedgerTransactionMapper transactionMapper;
    @Mock private LedgerTransactionPersonMapper relationMapper;
    @Mock private UserAccountMapper userMapper;
    @Mock private ChangeLogService changeLogService;

    private TransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(Ledger.class);
        initializeLambdaMetadata(LedgerMember.class);
        initializeLambdaMetadata(LedgerPerson.class);
        initializeLambdaMetadata(LedgerTransaction.class);
        initializeLambdaMetadata(LedgerTransactionPerson.class);
        initializeLambdaMetadata(UserAccount.class);
        service = new TransactionServiceImpl(
                ledgerMapper, memberMapper, personMapper, relationMapper, userMapper, changeLogService);
        ReflectionTestUtils.setField(service, "baseMapper", transactionMapper);
    }

    @Test
    void ownerUpdatesWithOneAtomicWriteBeforeRelationsAndLog() {
        LedgerTransaction target = transaction(31L, "transaction-uuid", 8L, 4, null);
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(transactionMapper.selectOne(any())).thenReturn(target);
        when(personMapper.selectList(any())).thenReturn(List.of(person(41L, "person-a")), List.of());
        when(transactionMapper.update(isNull(), any())).thenReturn(1);

        TransactionResp response = loggedIn(() -> service.update(
                "ledger-uuid", "transaction-uuid", updateReq(4, null, List.of("person-a"))));

        assertEquals(5, response.getVersion());
        assertNull(response.getPayerPersonUuid());
        assertNull(response.getNote());
        assertEquals(List.of("person-a"), response.getPersonUuids());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerTransaction>> update = transactionUpdateCaptor();
        verify(transactionMapper).update(isNull(), update.capture());
        assertAtomicWrapper(update.getValue(), "deleted_at IS NULL", 4, 5);
        assertSetColumns(update.getValue(), "type", "payer_person_id", "amount", "currency_code",
                "category", "note", "happened_at", "last_modified_by_user_id", "updated_at", "version");
        assertOperatorReadLockedAndScoped();
        ArgumentCaptor<Wrapper<LedgerPerson>> peopleRead = personWrapperCaptor();
        verify(personMapper).selectList(peopleRead.capture());
        assertTrue(peopleRead.getValue().getSqlSegment().contains("FOR UPDATE"));
        verify(transactionMapper, never()).updateById(any(LedgerTransaction.class));
        InOrder order = inOrder(transactionMapper, relationMapper, changeLogService);
        order.verify(transactionMapper).update(isNull(), any());
        order.verify(relationMapper).delete(any());
        order.verify(relationMapper).insert(any(LedgerTransactionPerson.class));
        order.verify(changeLogService).record(11L, "transaction", "transaction-uuid", "update", 7L);
    }

    @Test
    void createLocksOperatorThenUserThenMergedOrderedPeopleAndReusesLockedResponseData() {
        LedgerPerson payer = person(42L, "payer-person");
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(operator(LedgerRoles.EDITOR));
        when(userMapper.selectList(any())).thenReturn(List.of(user(7L, "creator-user", "Creator")));
        when(personMapper.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            if (sql.contains("ledger_id") && wrapper.getParamNameValuePairs().size() >= 3) {
                return List.of(person(41L, "person-a"), payer);
            }
            if (sql.contains("ledger_id")) {
                return List.of(person(41L, "person-a"));
            }
            return List.of(payer);
        });
        org.mockito.Mockito.lenient().when(personMapper.selectOne(any())).thenReturn(payer);
        when(transactionMapper.insert(any(LedgerTransaction.class))).thenAnswer(invocation -> {
            LedgerTransaction inserted = invocation.getArgument(0);
            inserted.setId(31L);
            inserted.setCreatedAt(LocalDateTime.of(2026, 8, 21, 12, 1));
            inserted.setUpdatedAt(LocalDateTime.of(2026, 8, 21, 12, 1));
            return 1;
        });
        TransactionCreateReq req = createReq("new-operation", "payer-person", List.of("person-a"));

        TransactionResp response = loggedIn(() -> service.create("ledger-uuid", req));

        assertEquals("creator-user", response.getCreatedByUserUuid());
        assertEquals("payer-person", response.getPayerPersonUuid());
        assertEquals(List.of("person-a"), response.getPersonUuids());
        assertOperatorReadLockedAndScoped();
        ArgumentCaptor<Wrapper<LedgerTransaction>> existingRead = transactionWrapperCaptor();
        verify(transactionMapper).selectOne(existingRead.capture());
        assertTrue(existingRead.getValue().getSqlSegment().contains("FOR UPDATE"));
        ArgumentCaptor<Wrapper<UserAccount>> userRead = userWrapperCaptor();
        verify(userMapper, times(1)).selectList(userRead.capture());
        assertOrderedForUpdate((AbstractWrapper<?, ?, ?>) userRead.getValue());
        ArgumentCaptor<Wrapper<LedgerPerson>> peopleRead = personWrapperCaptor();
        verify(personMapper, times(1)).selectList(peopleRead.capture());
        AbstractWrapper<?, ?, ?> peopleWrapper = (AbstractWrapper<?, ?, ?>) peopleRead.getValue();
        assertOrderedForUpdate(peopleWrapper);
        assertTrue(peopleWrapper.getParamNameValuePairs().containsValue("person-a"));
        assertTrue(peopleWrapper.getParamNameValuePairs().containsValue("payer-person"));
        verify(personMapper, never()).selectOne(any());
        InOrder order = inOrder(memberMapper, userMapper, personMapper, transactionMapper, relationMapper);
        order.verify(memberMapper).selectOne(any());
        order.verify(userMapper).selectList(any());
        order.verify(personMapper).selectList(any());
        order.verify(transactionMapper).insert(any(LedgerTransaction.class));
        order.verify(relationMapper).insert(any(LedgerTransactionPerson.class));
    }

    @Test
    void existingClientOperationReplayRechecksLockedPermissionThenUsesUserRelationPersonOrder() {
        LedgerTransaction existing = transaction(31L, "existing-transaction", 7L, 3, null);
        existing.setPayerPersonId(42L);
        existing.setLastModifiedByUserId(9L);
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(operator(LedgerRoles.EDITOR));
        when(transactionMapper.selectOne(any())).thenReturn(existing);
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(7L, "creator-user", "Creator"), user(9L, "modifier-user", "Modifier")));
        when(relationMapper.selectList(any())).thenReturn(List.of(relation(31L, 43L)));
        when(personMapper.selectList(any())).thenReturn(
                List.of(person(42L, "payer-person"), person(43L, "existing-person")));

        TransactionResp response = loggedIn(() -> service.create(
                "ledger-uuid", createReq("same-operation", "ignored-payer", List.of("ignored-person"))));

        assertEquals("existing-transaction", response.getUuid());
        assertEquals("creator-user", response.getCreatedByUserUuid());
        assertEquals("Modifier", response.getLastModifiedByNickname());
        assertEquals("payer-person", response.getPayerPersonUuid());
        assertEquals(List.of("existing-person"), response.getPersonUuids());
        assertOperatorReadLockedAndScoped();
        ArgumentCaptor<Wrapper<LedgerTransaction>> existingRead = transactionWrapperCaptor();
        verify(transactionMapper).selectOne(existingRead.capture());
        assertTrue(existingRead.getValue().getSqlSegment().contains("FOR UPDATE"));
        InOrder order = inOrder(userMapper, relationMapper, personMapper);
        order.verify(userMapper).selectList(any());
        order.verify(relationMapper).selectList(any());
        order.verify(personMapper).selectList(any());
        verify(userMapper, times(1)).selectList(any());
        verify(personMapper, times(1)).selectList(any());
        verify(transactionMapper, never()).insert(any(LedgerTransaction.class));
        verify(relationMapper, never()).delete(any());
        verify(relationMapper, never()).insert(any(LedgerTransactionPerson.class));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void adminDeletesWithAtomicVersionPredicateAndTypedTombstone() {
        LedgerTransaction target = transaction(31L, "transaction-uuid", 8L, 4, null);
        stubLedgerAndOperator(LedgerRoles.ADMIN);
        when(transactionMapper.selectOne(any())).thenReturn(target);
        when(transactionMapper.update(isNull(), any())).thenReturn(1);

        VersionMutationResp response = loggedIn(() -> invokeDelete(deleteReq(4)));

        assertEquals("transaction-uuid", response.getUuid());
        assertEquals(5, response.getVersion());
        assertEquals(true, response.getDeleted());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerTransaction>> update = transactionUpdateCaptor();
        verify(transactionMapper).update(isNull(), update.capture());
        assertAtomicWrapper(update.getValue(), "deleted_at IS NULL", 4, 5);
        assertSetColumns(update.getValue(), "deleted_at", "last_modified_by_user_id", "updated_at", "version");
        verify(transactionMapper, never()).updateById(any(LedgerTransaction.class));
        verify(relationMapper, never()).delete(any());
        verify(changeLogService).record(11L, "transaction", "transaction-uuid", "delete", 7L);
    }

    @Test
    void expensePayerValidationAndResponseUseCurrentLockedReads() {
        LedgerTransaction target = transaction(31L, "transaction-uuid", 8L, 4, null);
        LedgerPerson payer = person(42L, "payer-person");
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(transactionMapper.selectOne(any())).thenReturn(target);
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(7L, "modifier-user", "Modifier"), user(8L, "creator-user", "Creator")));
        when(personMapper.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) invocation.getArgument(0);
            wrapper.getSqlSegment();
            if (wrapper.getParamNameValuePairs().size() >= 3) {
                return List.of(person(41L, "person-a"), payer);
            }
            if (wrapper.getParamNameValuePairs().containsValue(42L)) {
                return List.of(payer);
            }
            return List.of(person(41L, "person-a"));
        });
        when(transactionMapper.update(isNull(), any())).thenReturn(1);
        TransactionUpdateReq req = updateReq(4, "payer-person", List.of("person-a"));
        req.setType(0);

        TransactionResp response = loggedIn(() -> service.update(
                "ledger-uuid", "transaction-uuid", req));

        assertEquals("payer-person", response.getPayerPersonUuid());
        verify(personMapper, never()).selectOne(any());
        ArgumentCaptor<Wrapper<LedgerPerson>> peopleRead = personWrapperCaptor();
        verify(personMapper, times(1)).selectList(peopleRead.capture());
        AbstractWrapper<?, ?, ?> peopleWrapper = (AbstractWrapper<?, ?, ?>) peopleRead.getValue();
        assertOrderedForUpdate(peopleWrapper);
        assertTrue(peopleWrapper.getParamNameValuePairs().containsValue("person-a"));
        assertTrue(peopleWrapper.getParamNameValuePairs().containsValue("payer-person"));
        ArgumentCaptor<Wrapper<UserAccount>> usersRead = userWrapperCaptor();
        verify(userMapper, times(1)).selectList(usersRead.capture());
        assertOrderedForUpdate((AbstractWrapper<?, ?, ?>) usersRead.getValue());
        InOrder lockOrder = inOrder(userMapper, personMapper, transactionMapper);
        lockOrder.verify(userMapper).selectList(any());
        lockOrder.verify(personMapper).selectList(any());
        lockOrder.verify(transactionMapper).update(isNull(), any());
    }

    @Test
    void ownerAndAdminCanMutateTransactionsCreatedByAnotherUser() {
        for (String role : List.of(LedgerRoles.OWNER, LedgerRoles.ADMIN)) {
            for (String operation : List.of("update", "delete", "restore")) {
                resetAll();
                boolean restore = "restore".equals(operation);
                stubLedgerAndOperator(role);
                when(transactionMapper.selectOne(any())).thenReturn(
                        transaction(31L, "transaction-uuid", 99L, 4, restore ? LocalDateTime.now() : null));
                when(personMapper.selectList(any())).thenReturn(List.of(person(41L, "person-a")), List.of());
                when(transactionMapper.update(isNull(), any())).thenReturn(1);

                loggedIn(() -> invoke(operation, updateReq(4, null, List.of("person-a"))));

                verify(transactionMapper).update(isNull(), any());
                verify(changeLogService).record(11L, "transaction", "transaction-uuid",
                        "delete".equals(operation) ? "delete" : "update", 7L);
            }
        }
    }

    @Test
    void editorCanUpdateDeleteAndRestoreOwnTransaction() {
        for (String operation : List.of("update", "delete", "restore")) {
            resetAll();
            boolean restore = "restore".equals(operation);
            stubLedgerAndOperator(LedgerRoles.EDITOR);
            when(transactionMapper.selectOne(any())).thenReturn(
                    transaction(31L, "transaction-uuid", 7L, 4, restore ? LocalDateTime.now() : null));
            when(personMapper.selectList(any())).thenReturn(List.of(person(41L, "person-a")), List.of());
            when(transactionMapper.update(isNull(), any())).thenReturn(1);

            Object response = loggedIn(() -> invoke(operation, updateReq(4, null, List.of("person-a"))));

            assertNotNull(response);
            verify(transactionMapper).update(isNull(), any());
        }
    }

    @Test
    void editorOtherAndViewerAreForbiddenBeforeConflictOrBusinessValidation() {
        for (String role : List.of(LedgerRoles.EDITOR, LedgerRoles.VIEWER)) {
            for (String operation : List.of("update", "delete", "restore")) {
                resetAll();
                boolean restore = "restore".equals(operation);
                stubLedgerAndOperator(role);
                when(transactionMapper.selectOne(any())).thenReturn(
                        transaction(31L, "transaction-uuid", 99L, 9,
                                restore ? LocalDateTime.now() : null));

                BusinessException exception = assertThrows(BusinessException.class,
                        () -> loggedIn(() -> invoke(operation, updateReq(4, "missing-payer", List.of("missing")))));

                assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
                assertOperatorReadLockedAndScoped();
                verify(personMapper, never()).selectOne(any());
                verify(personMapper, never()).selectList(any());
                verify(transactionMapper, never()).update(isNull(), any());
                verify(changeLogService, never()).record(any(), any(), any(), any(), any());
            }
        }
    }

    @Test
    void inactiveOperatorIsForbiddenBeforeTargetRead() {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update(
                        "ledger-uuid", "transaction-uuid", updateReq(4, null, List.of("person-a")))));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(transactionMapper, never()).selectOne(any());
        verify(transactionMapper, never()).update(isNull(), any());
    }

    @Test
    void staleUpdateReturnsCompleteSafeSnapshotBeforePayerAndPeopleValidation() {
        LedgerTransaction target = transaction(31L, "transaction-uuid", 8L, 5, null);
        target.setPayerPersonId(42L);
        target.setLastModifiedByUserId(9L);
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(transactionMapper.selectOne(any())).thenReturn(target);
        stubCurrentSnapshotRelationsAndUsers();

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update(
                        "ledger-uuid", "transaction-uuid", updateReq(4, "missing-payer", List.of("missing")))));

        assertTransactionConflict(exception, 4, 5, false);
        verify(personMapper, never()).selectOne(any());
        verify(transactionMapper, never()).update(isNull(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        assertCurrentSnapshotReadsLocked();
        InOrder lockOrder = inOrder(userMapper, relationMapper, personMapper);
        lockOrder.verify(userMapper).selectList(any());
        lockOrder.verify(relationMapper).selectList(any());
        lockOrder.verify(personMapper).selectList(any());
    }

    @Test
    void updateAndDeleteOfRemoteDeletedConflictAndActiveRestoreConflicts() {
        for (String operation : List.of("update", "delete", "restore")) {
            resetAll();
            boolean restore = "restore".equals(operation);
            stubLedgerAndOperator(LedgerRoles.OWNER);
            LedgerTransaction target = transaction(31L, "transaction-uuid", 8L, 4,
                    restore ? null : LocalDateTime.now());
            when(transactionMapper.selectOne(any())).thenReturn(target);

            VersionConflictException exception = assertThrows(VersionConflictException.class,
                    () -> loggedIn(() -> invoke(operation, updateReq(4, null, List.of("person-a")))));

            assertTransactionConflict(exception, 4, 4, !restore);
            verify(transactionMapper, never()).update(isNull(), any());
            verify(relationMapper, never()).delete(any());
            verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        }
    }

    @Test
    void missingTargetIsNotFoundAndMutationLookupIncludesDeletedRowsAndCurrentLock() {
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(transactionMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update(
                        "ledger-uuid", "missing", updateReq(4, null, List.of("person-a")))));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        ArgumentCaptor<Wrapper<LedgerTransaction>> read = transactionWrapperCaptor();
        verify(transactionMapper).selectOne(read.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read.getValue();
        assertTrue(wrapper.getSqlSegment().matches("(?s).*\\bledger_id\\b\\s*=.*"));
        assertTrue(wrapper.getSqlSegment().matches("(?s).*\\buuid\\b\\s*=.*"));
        assertFalse(wrapper.getSqlSegment().contains("deleted_at"));
        assertTrue(wrapper.getSqlSegment().contains("FOR UPDATE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(11L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("missing"));
    }

    @Test
    void failedConditionalUpdateReloadsLatestTargetReauthorizesAndNeverTouchesRelationsOrLog() {
        LedgerTransaction initial = transaction(31L, "transaction-uuid", 7L, 4, null);
        LedgerTransaction latest = transaction(31L, "transaction-uuid", 99L, 5, null);
        stubLedgerAndOperator(LedgerRoles.EDITOR);
        when(transactionMapper.selectOne(any())).thenReturn(initial, latest);
        when(personMapper.selectList(any())).thenReturn(List.of(person(41L, "person-a")));
        when(transactionMapper.update(isNull(), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update(
                        "ledger-uuid", "transaction-uuid", updateReq(4, null, List.of("person-a")))));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        ArgumentCaptor<Wrapper<LedgerTransaction>> reads = transactionWrapperCaptor();
        verify(transactionMapper, times(2)).selectOne(reads.capture());
        assertTrue(reads.getAllValues().get(1).getSqlSegment().contains("FOR UPDATE"));
        verify(relationMapper, never()).delete(any());
        verify(relationMapper, never()).insert(any(LedgerTransactionPerson.class));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void secondWriterGetsLatestCompleteSnapshotAndFailedWriteDoesNotReplacePeopleOrLog() {
        LedgerTransaction initial = transaction(31L, "transaction-uuid", 8L, 4, null);
        LedgerTransaction latest = transaction(31L, "transaction-uuid", 8L, 5, null);
        latest.setAmount(new BigDecimal("88.50"));
        latest.setPayerPersonId(42L);
        latest.setLastModifiedByUserId(9L);
        stubLedgerAndOperator(LedgerRoles.OWNER);
        when(transactionMapper.selectOne(any())).thenReturn(initial, latest);
        when(personMapper.selectList(any())).thenReturn(
                List.of(person(41L, "person-a")),
                List.of(person(42L, "payer-latest"), person(43L, "person-latest")));
        when(transactionMapper.update(isNull(), any())).thenReturn(0);
        when(relationMapper.selectList(any())).thenReturn(List.of(relation(31L, 43L)));
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(8L, "creator-user", "Creator"), user(9L, "modifier-user", "Modifier")));

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update(
                        "ledger-uuid", "transaction-uuid", updateReq(4, null, List.of("person-a")))));

        assertTransactionConflict(exception, 4, 5, false);
        TransactionResp snapshot = (TransactionResp) ((ConflictResp) exception.getData()).getRemoteSnapshot();
        assertEquals(new BigDecimal("88.50"), snapshot.getAmount());
        assertEquals(List.of("person-latest"), snapshot.getPersonUuids());
        assertEquals("payer-latest", snapshot.getPayerPersonUuid());
        assertEquals("creator-user", snapshot.getCreatedByUserUuid());
        assertEquals("Modifier", snapshot.getLastModifiedByNickname());
        verify(relationMapper, never()).delete(any());
        verify(relationMapper, never()).insert(any(LedgerTransactionPerson.class));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        assertCurrentSnapshotReadsLocked();
        InOrder lockOrder = inOrder(userMapper, personMapper, transactionMapper, relationMapper);
        lockOrder.verify(userMapper).selectList(any());
        lockOrder.verify(personMapper).selectList(any());
        lockOrder.verify(transactionMapper).update(isNull(), any());
        lockOrder.verify(relationMapper).selectList(any());
        lockOrder.verify(personMapper).selectList(any());
    }

    @Test
    void restoreUsesDeletedPredicateExplicitNullsThenReplacesPeopleAndLogsInTransaction() {
        LedgerTransaction target = transaction(31L, "transaction-uuid", 7L, 4, LocalDateTime.now());
        target.setPayerPersonId(42L);
        target.setNote("old note");
        stubLedgerAndOperator(LedgerRoles.EDITOR);
        when(transactionMapper.selectOne(any())).thenReturn(target);
        when(personMapper.selectList(any())).thenReturn(List.of(person(41L, "person-a")), List.of());
        when(transactionMapper.update(isNull(), any())).thenReturn(1);

        TransactionResp response = loggedIn(() -> invokeRestore(updateReq(4, null, List.of("person-a"))));

        assertEquals(5, response.getVersion());
        assertNull(response.getPayerPersonUuid());
        assertNull(response.getNote());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerTransaction>> update = transactionUpdateCaptor();
        verify(transactionMapper).update(isNull(), update.capture());
        assertAtomicWrapper(update.getValue(), "deleted_at IS NOT NULL", 4, 5);
        assertSetColumns(update.getValue(), "type", "payer_person_id", "amount", "currency_code",
                "category", "note", "happened_at", "last_modified_by_user_id", "deleted_at", "updated_at", "version");
        InOrder order = inOrder(transactionMapper, relationMapper, changeLogService);
        order.verify(transactionMapper).update(isNull(), any());
        order.verify(relationMapper).delete(any());
        order.verify(relationMapper).insert(any(LedgerTransactionPerson.class));
        order.verify(changeLogService).record(11L, "transaction", "transaction-uuid", "update", 7L);
    }

    @Test
    void controllerUsesValidatedBodiesExactPathsTypedIdempotencyAndReplayableResponses() throws Exception {
        TransactionResp transactionResponse = new TransactionResp();
        transactionResponse.setUuid("transaction-uuid");
        transactionResponse.setVersion(5);
        VersionMutationResp deleted = new VersionMutationResp("transaction-uuid", 5, true);
        TransactionService transactionService = (TransactionService) Proxy.newProxyInstance(
                TransactionService.class.getClassLoader(), new Class<?>[]{TransactionService.class},
                (proxy, method, args) -> "delete".equals(method.getName()) ? deleted : transactionResponse);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(4);
            return supplier.get();
        });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TransactionController(transactionService, idempotencyService)).build();
        String body = "{\"version\":4,\"type\":1,\"amount\":12.50,\"currencyCode\":\"CNY\"," +
                "\"category\":\"income\",\"happenedAt\":\"2026-08-21T12:00:00\"," +
                "\"personUuids\":[\"person-a\"]}";

        mvc.perform(put("/api/ledgers/ledger-uuid/transactions/transaction-uuid")
                        .header("Idempotency-Key", "update-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("update-key"), eq("PUT"),
                eq("/api/ledgers/ledger-uuid/transactions/transaction-uuid"), eq(TransactionResp.class), any());

        mvc.perform(delete("/api/ledgers/ledger-uuid/transactions/transaction-uuid")
                        .header("Idempotency-Key", "delete-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("transaction-uuid"))
                .andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("delete-key"), eq("DELETE"),
                eq("/api/ledgers/ledger-uuid/transactions/transaction-uuid"), eq(VersionMutationResp.class), any());
        verify(idempotencyService, never()).executeVoid(any(), any(), any(), any());
        mvc.perform(delete("/api/ledgers/ledger-uuid/transactions/transaction-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/ledgers/ledger-uuid/transactions/transaction-uuid/restore")
                        .header("Idempotency-Key", "restore-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("restore-key"), eq("POST"),
                eq("/api/ledgers/ledger-uuid/transactions/transaction-uuid/restore"),
                eq(TransactionResp.class), any());
    }

    @Test
    void lifecycleMethodsAreTransactionalAndExposeUniformVersionedSignatures() throws Exception {
        assertTransactional("update", TransactionUpdateReq.class);
        assertTransactional("delete", VersionDeleteReq.class);
        assertTransactional("restore", TransactionUpdateReq.class);
        assertEquals(TransactionResp.class, TransactionService.class
                .getMethod("update", String.class, String.class, TransactionUpdateReq.class).getReturnType());
        assertEquals(VersionMutationResp.class, TransactionService.class
                .getMethod("delete", String.class, String.class, VersionDeleteReq.class).getReturnType());
        assertEquals(TransactionResp.class, TransactionService.class
                .getMethod("restore", String.class, String.class, TransactionUpdateReq.class).getReturnType());
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.simon.ledger.dto.req.TransactionDeleteReq"));
    }

    private void assertTransactional(String name, Class<?> requestType) throws Exception {
        Method method = TransactionServiceImpl.class.getMethod(
                name, String.class, String.class, requestType);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertEquals(Set.of(Exception.class), Set.of(annotation.rollbackFor()));
    }

    private Object invoke(String operation, TransactionUpdateReq req) {
        return switch (operation) {
            case "update" -> service.update("ledger-uuid", "transaction-uuid", req);
            case "delete" -> invokeDelete(deleteReq(req.getVersion()));
            case "restore" -> invokeRestore(req);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private VersionMutationResp invokeDelete(VersionDeleteReq req) {
        return invokeVersioned("delete", VersionDeleteReq.class, req);
    }

    private TransactionResp invokeRestore(TransactionUpdateReq req) {
        return invokeVersioned("restore", TransactionUpdateReq.class, req);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeVersioned(String methodName, Class<?> requestType, Object req) {
        try {
            Method method = TransactionServiceImpl.class.getMethod(
                    methodName, String.class, String.class, requestType);
            return (T) method.invoke(service, "ledger-uuid", "transaction-uuid", req);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Missing versioned transaction lifecycle method: " + methodName, exception);
        }
    }

    private TransactionUpdateReq updateReq(int version, String payerUuid, List<String> personUuids) {
        TransactionUpdateReq req = new TransactionUpdateReq();
        req.setVersion(version);
        req.setType(1);
        req.setPayerPersonUuid(payerUuid);
        req.setAmount(new BigDecimal("12.50"));
        req.setCurrencyCode(" cny ");
        req.setCategory(" income ");
        req.setNote("   ");
        req.setHappenedAt(LocalDateTime.of(2026, 8, 21, 12, 0));
        req.setPersonUuids(personUuids);
        return req;
    }

    private TransactionCreateReq createReq(String clientOperationId, String payerUuid, List<String> personUuids) {
        TransactionCreateReq req = new TransactionCreateReq();
        req.setType(0);
        req.setPayerPersonUuid(payerUuid);
        req.setAmount(new BigDecimal("12.50"));
        req.setCurrencyCode(" cny ");
        req.setCategory(" expense ");
        req.setNote("   ");
        req.setHappenedAt(LocalDateTime.of(2026, 8, 21, 12, 0));
        req.setClientOperationId(clientOperationId);
        req.setPersonUuids(personUuids);
        return req;
    }

    private VersionDeleteReq deleteReq(int version) {
        VersionDeleteReq req = new VersionDeleteReq();
        req.setVersion(version);
        return req;
    }

    private void stubLedgerAndOperator(String role) {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(operator(role));
    }

    private void stubCurrentSnapshotRelationsAndUsers() {
        when(relationMapper.selectList(any())).thenReturn(List.of(relation(31L, 43L)));
        when(personMapper.selectList(any())).thenReturn(
                List.of(person(42L, "payer-latest"), person(43L, "person-latest")));
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(8L, "creator-user", "Creator"), user(9L, "modifier-user", "Modifier")));
    }

    private void assertTransactionConflict(VersionConflictException exception, int submitted,
                                           int remote, boolean deleted) {
        ConflictResp conflict = assertInstanceOf(ConflictResp.class, exception.getData());
        assertEquals("transaction", conflict.getEntityType());
        assertEquals("transaction-uuid", conflict.getEntityUuid());
        assertEquals(submitted, conflict.getSubmittedVersion());
        assertEquals(remote, conflict.getRemoteVersion());
        assertEquals(deleted, conflict.getRemoteDeleted());
        TransactionResp snapshot = assertInstanceOf(TransactionResp.class, conflict.getRemoteSnapshot());
        assertEquals("transaction-uuid", snapshot.getUuid());
        assertEquals("ledger-uuid", snapshot.getLedgerUuid());
        assertEquals(remote, snapshot.getVersion());
        assertEquals(Set.of("uuid", "ledgerUuid", "type", "payerPersonUuid", "amount", "currencyCode",
                        "category", "note", "createdByUserUuid", "createdByNickname", "createdByAvatar",
                        "lastModifiedByUserUuid", "lastModifiedByNickname", "lastModifiedByAvatar",
                        "clientOperationId", "version", "happenedAt", "createdAt", "updatedAt", "personUuids"),
                Arrays.stream(TransactionResp.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getName).collect(Collectors.toSet()));
    }

    private void assertCurrentSnapshotReadsLocked() {
        ArgumentCaptor<Wrapper<LedgerTransactionPerson>> relationRead = relationWrapperCaptor();
        verify(relationMapper).selectList(relationRead.capture());
        assertOrderedForUpdate((AbstractWrapper<?, ?, ?>) relationRead.getValue());
        ArgumentCaptor<Wrapper<LedgerPerson>> personReads = personWrapperCaptor();
        verify(personMapper, atLeast(1)).selectList(personReads.capture());
        List<Wrapper<LedgerPerson>> reads = personReads.getAllValues();
        assertOrderedForUpdate((AbstractWrapper<?, ?, ?>) reads.get(reads.size() - 1));
        ArgumentCaptor<Wrapper<UserAccount>> userRead = userWrapperCaptor();
        verify(userMapper, times(1)).selectList(userRead.capture());
        assertOrderedForUpdate((AbstractWrapper<?, ?, ?>) userRead.getValue());
    }

    private void assertOrderedForUpdate(AbstractWrapper<?, ?, ?> wrapper) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.matches("(?s).*ORDER BY\\s+id\\s+ASC.*"), () -> "missing stable id order in " + sql);
        assertTrue(sql.contains("FOR UPDATE"), () -> "missing current-read lock in " + sql);
    }

    private void assertAtomicWrapper(LambdaUpdateWrapper<?> wrapper, String deletedPredicate,
                                     int submittedVersion, int nextVersion) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.matches("(?s).*\\bid\\b\\s*=.*"));
        assertTrue(sql.matches("(?s).*\\bversion\\b\\s*=.*"));
        assertTrue(sql.contains(deletedPredicate));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(31L));
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
        ArgumentCaptor<Wrapper<LedgerMember>> read = memberWrapperCaptor();
        verify(memberMapper).selectOne(read.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read.getValue();
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
        return member;
    }

    private LedgerTransaction transaction(Long id, String uuid, Long creatorId,
                                          int version, LocalDateTime deletedAt) {
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setId(id);
        transaction.setUuid(uuid);
        transaction.setLedgerId(11L);
        transaction.setType(0);
        transaction.setAmount(new BigDecimal("42.00"));
        transaction.setCurrencyCode("CNY");
        transaction.setCategory("meal");
        transaction.setNote("remote note");
        transaction.setCreatedByUserId(creatorId);
        transaction.setClientOperationId("client-operation");
        transaction.setVersion(version);
        transaction.setHappenedAt(LocalDateTime.of(2026, 8, 20, 12, 0));
        transaction.setCreatedAt(LocalDateTime.of(2026, 8, 20, 12, 1));
        transaction.setUpdatedAt(LocalDateTime.of(2026, 8, 20, 12, 2));
        transaction.setDeletedAt(deletedAt);
        return transaction;
    }

    private LedgerPerson person(Long id, String uuid) {
        LedgerPerson person = new LedgerPerson();
        person.setId(id);
        person.setUuid(uuid);
        person.setLedgerId(11L);
        person.setName(uuid);
        return person;
    }

    private LedgerTransactionPerson relation(Long transactionId, Long personId) {
        LedgerTransactionPerson relation = new LedgerTransactionPerson();
        relation.setTransactionId(transactionId);
        relation.setPersonId(personId);
        return relation;
    }

    private UserAccount user(Long id, String uuid, String nickname) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUuid(uuid);
        user.setNickname(nickname);
        user.setAvatar(uuid + ".png");
        return user;
    }

    private void resetAll() {
        reset(ledgerMapper, memberMapper, personMapper, transactionMapper,
                relationMapper, userMapper, changeLogService);
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
    private ArgumentCaptor<LambdaUpdateWrapper<LedgerTransaction>> transactionUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LedgerTransaction>> transactionWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LedgerMember>> memberWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LedgerTransactionPerson>> relationWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<LedgerPerson>> personWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<UserAccount>> userWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @FunctionalInterface
    private interface ServiceCall<T> {
        T call();
    }
}

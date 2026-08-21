package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.LedgerUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.LedgerResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.PersonService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceConcurrencyTests {

    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper memberMapper;
    @Mock private UserAccountMapper userMapper;
    @Mock private ChangeLogService changeLogService;
    @Mock private PersonService personService;

    private LedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(Ledger.class);
        initializeLambdaMetadata(LedgerMember.class);
        initializeLambdaMetadata(UserAccount.class);
        service = new LedgerServiceImpl(memberMapper, userMapper, changeLogService, personService);
        ReflectionTestUtils.setField(service, "baseMapper", ledgerMapper);
    }

    @Test
    void matchingVersionUpdatesLedgerWithAtomicPredicateAndReturnsThree() {
        Ledger ledger = ledger(2, null);
        LedgerMember owner = member("owner", 5, null);
        stubLedgerAndMember(ledger, owner);
        when(ledgerMapper.update(isNull(), any())).thenReturn(1);
        stubSnapshotMembers(owner);

        LedgerResp response = loggedIn(() -> service.update("ledger-uuid", updateReq(2)));

        assertEquals(3, response.getVersion());
        assertEquals("Updated ledger", response.getName());
        assertEquals("USD", response.getBaseCurrencyCode());
        assertEquals(new BigDecimal("7.25"), response.getExchangeRateToCny());
        verifyLedgerUpdate("deleted_at IS NULL", 2, 3);
        verify(ledgerMapper, never()).updateById(any(Ledger.class));
        verify(changeLogService).record(11L, "ledger", "ledger-uuid", "update", 7L);
    }

    @Test
    void staleAndRemoteDeletedUpdateReturnFullSafeLedgerConflictWithoutWritingLog() {
        Ledger stale = ledger(3, null);
        LedgerMember owner = member("owner", 5, null);
        stubLedgerAndMember(stale, owner);
        stubSnapshotMembers(owner);

        VersionConflictException staleException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", updateReq(2))));
        assertLedgerConflict(staleException, 2, 3, false, "owner");

        Ledger deleted = ledger(2, LocalDateTime.of(2026, 8, 20, 12, 0));
        when(ledgerMapper.selectOne(any())).thenReturn(deleted);
        VersionConflictException deletedException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", updateReq(2))));
        assertLedgerConflict(deletedException, 2, 2, true, "owner");

        verify(ledgerMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deleteIsOwnerOnlyAndUsesVersionedSoftDelete() {
        Ledger ledger = ledger(2, null);
        LedgerMember owner = member("owner", 5, null);
        stubLedgerAndMember(ledger, owner);
        when(ledgerMapper.update(isNull(), any())).thenReturn(1);

        VersionMutationResp response = loggedIn(() -> service.delete("ledger-uuid", deleteReq(2)));

        assertEquals("ledger-uuid", response.getUuid());
        assertEquals(3, response.getVersion());
        assertTrue(response.getDeleted());
        ArgumentCaptor<LambdaUpdateWrapper<Ledger>> deleteCaptor = ledgerUpdateCaptor();
        verify(ledgerMapper).update(isNull(), deleteCaptor.capture());
        assertAtomicWrapper(deleteCaptor.getValue(), "deleted_at IS NULL", 2, 3);
        assertTrue(deleteCaptor.getValue().getSqlSet().contains("deleted_at"));
        assertTrue(deleteCaptor.getValue().getSqlSet().contains("updated_at"));
        verify(changeLogService).record(11L, "ledger", "ledger-uuid", "delete", 7L);

        when(memberMapper.selectOne(any())).thenReturn(member("admin", 5, null));
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.delete("ledger-uuid", deleteReq(2))));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getErrorCode());
    }

    @Test
    void staleAndRemoteDeletedDeleteConflictWithoutWritingLog() {
        Ledger stale = ledger(3, null);
        LedgerMember owner = member("owner", 5, null);
        stubLedgerAndMember(stale, owner);
        stubSnapshotMembers(owner);

        VersionConflictException staleException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.delete("ledger-uuid", deleteReq(2))));
        assertLedgerConflict(staleException, 2, 3, false, "owner");

        Ledger deleted = ledger(2, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(deleted);
        VersionConflictException deletedException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.delete("ledger-uuid", deleteReq(2))));
        assertLedgerConflict(deletedException, 2, 2, true, "owner");
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void restoreRequiresOwnerDeletedTargetAndUpdatesDataAtomically() {
        Ledger ledger = ledger(2, LocalDateTime.of(2026, 8, 20, 12, 0));
        LedgerMember owner = member("owner", 5, null);
        stubLedgerAndMember(ledger, owner);
        when(ledgerMapper.update(isNull(), any())).thenReturn(1);
        stubSnapshotMembers(owner);

        LedgerResp response = loggedIn(() -> service.restore("ledger-uuid", updateReq(2)));

        assertEquals(3, response.getVersion());
        assertEquals("Updated ledger", response.getName());
        assertNull(ledger.getDeletedAt());
        verifyLedgerUpdate("deleted_at IS NOT NULL", 2, 3);
        verify(changeLogService).record(11L, "ledger", "ledger-uuid", "update", 7L);
    }

    @Test
    void activeOrStaleRestoreConflictsAndAdminCannotRestore() {
        Ledger active = ledger(2, null);
        LedgerMember owner = member("owner", 5, null);
        stubLedgerAndMember(active, owner);
        stubSnapshotMembers(owner);
        VersionConflictException activeException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.restore("ledger-uuid", updateReq(2))));
        assertLedgerConflict(activeException, 2, 2, false, "owner");

        Ledger stale = ledger(3, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(stale);
        VersionConflictException staleException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.restore("ledger-uuid", updateReq(2))));
        assertLedgerConflict(staleException, 2, 3, true, "owner");

        when(memberMapper.selectOne(any())).thenReturn(member("admin", 5, null));
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.restore("ledger-uuid", updateReq(3))));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getErrorCode());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void leaveVersionsOwnMembershipAndReturnsMemberUuid() {
        Ledger ledger = ledger(8, null);
        LedgerMember member = member("editor", 4, null);
        stubLedgerAndMember(ledger, member);
        when(memberMapper.update(isNull(), any())).thenReturn(1);

        VersionMutationResp response = loggedIn(() -> service.leave("ledger-uuid", deleteReq(4)));

        assertEquals("member-uuid", response.getUuid());
        assertEquals(5, response.getVersion());
        assertTrue(response.getDeleted());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> captor = memberUpdateCaptor();
        verify(memberMapper).update(isNull(), captor.capture());
        assertAtomicWrapper(captor.getValue(), "deleted_at IS NULL", 4, 5);
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(1));
        verify(memberMapper, never()).updateById(any(LedgerMember.class));
        verify(changeLogService).record(11L, "member", "member-uuid", "delete", 7L);
    }

    @Test
    void staleOrAlreadyRemovedLeaveReturnsSafeMemberConflictAndOwnerIsForbidden() {
        Ledger ledger = ledger(8, null);
        LedgerMember stale = member("editor", 5, null);
        stubLedgerAndMember(ledger, stale);
        when(userMapper.selectById(7L)).thenReturn(user());

        VersionConflictException staleException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.leave("ledger-uuid", deleteReq(4))));
        assertMemberConflict(staleException, 4, 5, false);

        LedgerMember removed = member("editor", 4, LocalDateTime.now());
        when(memberMapper.selectOne(any())).thenReturn(removed);
        VersionConflictException removedException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.leave("ledger-uuid", deleteReq(4))));
        assertMemberConflict(removedException, 4, 4, true);

        when(memberMapper.selectOne(any())).thenReturn(member("owner", 4, null));
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.leave("ledger-uuid", deleteReq(4))));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getErrorCode());

        when(memberMapper.selectOne(any())).thenReturn(member("owner", 9, null));
        VersionConflictException staleOwner = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.leave("ledger-uuid", deleteReq(4))));
        assertMemberConflict(staleOwner, 4, 9, false, "owner");
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void missingLedgerIsNotFoundAndMissingMembershipIsForbidden() {
        when(ledgerMapper.selectOne(any())).thenReturn(null);
        BusinessException missingLedger = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.update("missing", updateReq(2))));
        assertEquals(ErrorCode.NOT_FOUND, missingLedger.getErrorCode());

        when(ledgerMapper.selectOne(any())).thenReturn(ledger(2, null));
        when(memberMapper.selectOne(any())).thenReturn(null);
        BusinessException missingMember = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.leave("ledger-uuid", deleteReq(2))));
        assertEquals(ErrorCode.FORBIDDEN, missingMember.getErrorCode());
    }

    @Test
    void affectedZeroReloadsLatestLedgerForUpdateAndDoesNotLog() {
        Ledger initial = ledger(2, null);
        Ledger latest = ledger(4, LocalDateTime.now());
        LedgerMember owner = member("owner", 5, null);
        when(ledgerMapper.selectOne(any())).thenReturn(initial, latest);
        when(memberMapper.selectOne(any())).thenReturn(owner);
        when(ledgerMapper.update(isNull(), any())).thenReturn(0);
        stubSnapshotMembers(owner);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.update("ledger-uuid", updateReq(2))));

        assertLedgerConflict(exception, 2, 4, true, "owner");
        ArgumentCaptor<Wrapper<Ledger>> reload = ledgerWrapperCaptor();
        verify(ledgerMapper, org.mockito.Mockito.times(2)).selectOne(reload.capture());
        assertTrue(reload.getAllValues().get(1).getSqlSegment().contains("FOR UPDATE"));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroReloadsLatestMemberForUpdateAndDoesNotLog() {
        Ledger ledger = ledger(2, null);
        LedgerMember initial = member("editor", 4, null);
        LedgerMember latest = member("editor", 6, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(ledger);
        when(memberMapper.selectOne(any())).thenReturn(initial, latest);
        when(memberMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(7L)).thenReturn(user());

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.leave("ledger-uuid", deleteReq(4))));

        assertMemberConflict(exception, 4, 6, true);
        ArgumentCaptor<Wrapper<LedgerMember>> reload = memberWrapperCaptor();
        verify(memberMapper, org.mockito.Mockito.times(2)).selectOne(reload.capture());
        assertTrue(reload.getAllValues().get(1).getSqlSegment().contains("FOR UPDATE"));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void writeMethodsRemainTransactionalForAllExceptions() throws Exception {
        assertTransactional("update", LedgerUpdateReq.class);
        assertTransactional("delete", VersionDeleteReq.class);
        assertTransactional("restore", LedgerUpdateReq.class);
        assertTransactional("leave", VersionDeleteReq.class);
    }

    private void assertTransactional(String name, Class<?> requestType) throws Exception {
        Method method = LedgerServiceImpl.class.getMethod(name, String.class, requestType);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertEquals(Set.of(Exception.class), Set.of(annotation.rollbackFor()));
    }

    private void verifyLedgerUpdate(String deletedPredicate, int submitted, int next) {
        ArgumentCaptor<LambdaUpdateWrapper<Ledger>> captor = ledgerUpdateCaptor();
        verify(ledgerMapper).update(isNull(), captor.capture());
        assertAtomicWrapper(captor.getValue(), deletedPredicate, submitted, next);
        assertTrue(captor.getValue().getSqlSet().contains("name"));
        assertTrue(captor.getValue().getSqlSet().contains("base_currency_code"));
        assertTrue(captor.getValue().getSqlSet().contains("exchange_rate_to_cny"));
        assertTrue(captor.getValue().getSqlSet().contains("updated_at"));
    }

    private void assertAtomicWrapper(LambdaUpdateWrapper<?> wrapper, String deletedPredicate, int submitted, int next) {
        assertTrue(wrapper.getSqlSegment().contains("id"));
        assertTrue(wrapper.getSqlSegment().contains("version"));
        assertTrue(wrapper.getSqlSegment().contains(deletedPredicate));
        assertTrue(wrapper.getSqlSet().contains("version"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(submitted));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(next));
    }

    private void assertLedgerConflict(VersionConflictException exception, int submitted, int remote,
                                      boolean deleted, String role) {
        ConflictResp conflict = assertInstanceOf(ConflictResp.class, exception.getData());
        assertEquals("ledger", conflict.getEntityType());
        assertEquals("ledger-uuid", conflict.getEntityUuid());
        assertEquals(submitted, conflict.getSubmittedVersion());
        assertEquals(remote, conflict.getRemoteVersion());
        assertEquals(deleted, conflict.getRemoteDeleted());
        LedgerResp snapshot = assertInstanceOf(LedgerResp.class, conflict.getRemoteSnapshot());
        assertEquals(Set.of("uuid", "name", "baseCurrencyCode", "exchangeRateToCny", "version", "role",
                        "memberCount", "members", "createdAt", "updatedAt"),
                java.util.Arrays.stream(LedgerResp.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals("ledger-uuid", snapshot.getUuid());
        assertEquals(role, snapshot.getRole());
        assertEquals(remote, snapshot.getVersion());
        assertEquals(1, snapshot.getMemberCount());
        assertFalse(snapshot.getMembers().isEmpty());
    }

    private void assertMemberConflict(VersionConflictException exception, int submitted, int remote, boolean deleted) {
        assertMemberConflict(exception, submitted, remote, deleted, "editor");
    }

    private void assertMemberConflict(VersionConflictException exception, int submitted, int remote,
                                      boolean deleted, String role) {
        ConflictResp conflict = assertInstanceOf(ConflictResp.class, exception.getData());
        assertEquals("member", conflict.getEntityType());
        assertEquals("member-uuid", conflict.getEntityUuid());
        assertEquals(submitted, conflict.getSubmittedVersion());
        assertEquals(remote, conflict.getRemoteVersion());
        assertEquals(deleted, conflict.getRemoteDeleted());
        MemberResp snapshot = assertInstanceOf(MemberResp.class, conflict.getRemoteSnapshot());
        assertEquals("user-uuid", snapshot.getUserUuid());
        assertEquals("Test User", snapshot.getNickname());
        assertEquals("avatar.png", snapshot.getAvatar());
        assertEquals(role, snapshot.getRole());
        assertEquals(1, snapshot.getStatus());
        assertEquals(remote, snapshot.getVersion());
        assertNotNull(snapshot.getJoinedAt());
    }

    private void stubLedgerAndMember(Ledger ledger, LedgerMember member) {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger);
        when(memberMapper.selectOne(any())).thenReturn(member);
    }

    private void stubSnapshotMembers(LedgerMember member) {
        when(memberMapper.selectList(any())).thenReturn(List.of(member));
        when(userMapper.selectList(any())).thenReturn(List.of(user()));
    }

    private Ledger ledger(int version, LocalDateTime deletedAt) {
        Ledger ledger = new Ledger();
        ledger.setId(11L);
        ledger.setUuid("ledger-uuid");
        ledger.setName("Old ledger");
        ledger.setBaseCurrencyCode("CNY");
        ledger.setExchangeRateToCny(BigDecimal.ONE);
        ledger.setOwnerUserId(7L);
        ledger.setVersion(version);
        ledger.setDeletedAt(deletedAt);
        ledger.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ledger.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return ledger;
    }

    private LedgerMember member(String role, int version, LocalDateTime deletedAt) {
        LedgerMember member = new LedgerMember();
        member.setId(21L);
        member.setUuid("member-uuid");
        member.setLedgerId(11L);
        member.setUserId(7L);
        member.setRole(role);
        member.setStatus(1);
        member.setVersion(version);
        member.setJoinedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        member.setDeletedAt(deletedAt);
        return member;
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setUuid("user-uuid");
        user.setNickname("Test User");
        user.setAvatar("avatar.png");
        return user;
    }

    private LedgerUpdateReq updateReq(int version) {
        LedgerUpdateReq req = new LedgerUpdateReq();
        req.setVersion(version);
        req.setName(" Updated ledger ");
        req.setBaseCurrencyCode(" usd ");
        req.setExchangeRateToCny(new BigDecimal("7.25"));
        return req;
    }

    private VersionDeleteReq deleteReq(int version) {
        VersionDeleteReq req = new VersionDeleteReq();
        req.setVersion(version);
        return req;
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
    private ArgumentCaptor<LambdaUpdateWrapper<Ledger>> ledgerUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> memberUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<Ledger>> ledgerWrapperCaptor() {
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

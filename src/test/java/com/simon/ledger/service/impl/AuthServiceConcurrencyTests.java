package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.AuthProfileUpdateReq;
import com.simon.ledger.dto.resp.AuthUserResp;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceConcurrencyTests {

    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private LedgerPersonMapper ledgerPersonMapper;
    @Mock
    private LedgerMapper ledgerMapper;
    @Mock
    private ChangeLogService changeLogService;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(UserAccount.class);
        initializeLambdaMetadata(Ledger.class);
        initializeLambdaMetadata(LedgerPerson.class);
        service = new AuthServiceImpl(ledgerPersonMapper, ledgerMapper, changeLogService);
        ReflectionTestUtils.setField(service, "baseMapper", userAccountMapper);
    }

    private void initializeLambdaMetadata(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @Test
    void matchingVersionUpdatesProfileAtomicallyAndReturnsIncrementedVersion() {
        UserAccount user = user(2);
        LedgerPerson person = person();
        when(ledgerPersonMapper.selectObjs(any())).thenReturn(List.of(11L));
        when(ledgerMapper.selectList(any())).thenReturn(List.of(ledger(11L)));
        when(userAccountMapper.selectOne(any())).thenReturn(user);
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        when(ledgerPersonMapper.selectList(any())).thenReturn(List.of(person));
        when(ledgerPersonMapper.update(isNull(), any())).thenReturn(1);

        AuthUserResp response = withLoggedInUser(() -> service.updateProfile(request(2)));

        assertEquals(3, response.getVersion());
        assertEquals("new nickname", response.getNickname());
        assertEquals("https://example.test/new.png", response.getAvatar());
        assertEquals(3, user.getVersion());
        assertNotNull(user.getUpdatedAt());
        verify(userAccountMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
        verifyUserUpdateWrapper(3);
        verifyPersonSyncWrapper();
        verify(changeLogService).record(11L, "person", "person-uuid", "update", 7L);
    }

    @Test
    void locksDistinctLedgersInIdOrderBeforeCurrentUserPeopleAndChangeLogs() {
        UserAccount user = user(2);
        LedgerPerson first = person(8L, "person-first", 11L);
        LedgerPerson second = person(9L, "person-second", 22L);
        when(ledgerPersonMapper.selectObjs(any())).thenReturn(List.of(22L, 11L, 22L));
        when(ledgerMapper.selectList(any())).thenReturn(List.of(ledger(11L), ledger(22L)));
        when(userAccountMapper.selectOne(any())).thenReturn(user);
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        when(ledgerPersonMapper.selectList(any())).thenReturn(List.of(first, second));
        when(ledgerPersonMapper.update(isNull(), any())).thenReturn(1);

        withLoggedInUser(() -> service.updateProfile(request(2)));

        ArgumentCaptor<Wrapper<LedgerPerson>> discovery = personWrapperCaptor();
        ArgumentCaptor<Wrapper<Ledger>> ledgerLock = ledgerWrapperCaptor();
        ArgumentCaptor<Wrapper<UserAccount>> userLock = wrapperCaptor();
        ArgumentCaptor<Wrapper<LedgerPerson>> peopleLock = personWrapperCaptor();
        InOrder order = inOrder(ledgerPersonMapper, ledgerMapper, userAccountMapper, changeLogService);
        order.verify(ledgerPersonMapper).selectObjs(discovery.capture());
        order.verify(ledgerMapper).selectList(ledgerLock.capture());
        order.verify(userAccountMapper).selectOne(userLock.capture());
        order.verify(userAccountMapper).update(isNull(), any());
        order.verify(ledgerPersonMapper).selectList(peopleLock.capture());
        order.verify(ledgerPersonMapper).update(isNull(), any());
        order.verify(changeLogService).record(11L, "person", "person-first", "update", 7L);
        order.verify(ledgerPersonMapper).update(isNull(), any());
        order.verify(changeLogService).record(22L, "person", "person-second", "update", 7L);

        assertDiscoveryRead(discovery.getValue());
        assertLedgerNamespaceLock(ledgerLock.getValue(), 11L, 22L);
        assertCurrentUserLock(userLock.getValue());
        assertCurrentPeopleLock(peopleLock.getValue());
        verify(userAccountMapper, never()).selectById(7L);
    }

    @Test
    void noVisibleLinkOrFutureUncommittedLinkLetsProfileWinUserAndSkipsLedgerLock() {
        UserAccount user = user(2);
        // A Person mutation that has not linked yet may already hold its ledger lock. Because this
        // profile wins the user lock, it linearizes first; that Person mutation links after commit.
        when(ledgerPersonMapper.selectObjs(any())).thenReturn(List.of());
        when(userAccountMapper.selectOne(any())).thenReturn(user);
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        when(ledgerPersonMapper.selectList(any())).thenReturn(List.of());

        AuthUserResp response = withLoggedInUser(() -> service.updateProfile(request(2)));

        assertEquals(3, response.getVersion());
        verify(ledgerMapper, never()).selectList(any());
        ArgumentCaptor<Wrapper<UserAccount>> userLock = wrapperCaptor();
        verify(userAccountMapper).selectOne(userLock.capture());
        assertCurrentUserLock(userLock.getValue());
        ArgumentCaptor<Wrapper<LedgerPerson>> peopleLock = personWrapperCaptor();
        verify(ledgerPersonMapper).selectList(peopleLock.capture());
        assertCurrentPeopleLock(peopleLock.getValue());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void initialStaleVersionThrowsSafeConflictWithoutAttemptingUpdate() {
        UserAccount user = user(3);
        when(userAccountMapper.selectOne(any())).thenReturn(user);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));

        assertProfileConflict(exception, 2, 3, user);
        verify(userAccountMapper, never()).update(any(), any());
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
    }

    @Test
    void lostRaceReloadsCurrentUserAndThrowsSafeConflict() {
        UserAccount initial = user(2);
        UserAccount remote = user(4);
        remote.setNickname("remote nickname");
        remote.setAvatar("https://example.test/remote.png");
        when(userAccountMapper.selectOne(any())).thenReturn(initial, remote);
        when(userAccountMapper.update(isNull(), any())).thenReturn(0);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));

        assertProfileConflict(exception, 2, 4, remote);
        ArgumentCaptor<Wrapper<UserAccount>> reload = wrapperCaptor();
        verify(userAccountMapper, times(2)).selectOne(reload.capture());
        for (Wrapper<UserAccount> currentRead : reload.getAllValues()) {
            assertTrue(currentRead.getSqlSegment().contains("FOR UPDATE"));
        }
    }

    @Test
    void disabledAndMissingAccountsKeepExistingAuthorizationBehavior() {
        UserAccount disabled = user(2);
        disabled.setStatus(2);
        when(userAccountMapper.selectOne(any())).thenReturn(disabled);
        BusinessException disabledException = assertThrows(BusinessException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));
        assertEquals(ErrorCode.FORBIDDEN, disabledException.getErrorCode());

        when(userAccountMapper.selectOne(any())).thenReturn(null);
        BusinessException missingException = assertThrows(BusinessException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));
        assertEquals(ErrorCode.UNAUTHORIZED, missingException.getErrorCode());

        when(userAccountMapper.selectOne(any())).thenReturn(null);
        BusinessException deletedException = assertThrows(BusinessException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));
        assertEquals(ErrorCode.UNAUTHORIZED, deletedException.getErrorCode());
    }

    @Test
    void conditionalPersonSyncAffectedZeroIsNotLogged() {
        when(ledgerPersonMapper.selectObjs(any())).thenReturn(List.of(11L));
        when(ledgerMapper.selectList(any())).thenReturn(List.of(ledger(11L)));
        when(userAccountMapper.selectOne(any())).thenReturn(user(2));
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        when(ledgerPersonMapper.selectList(any())).thenReturn(List.of(person()));
        when(ledgerPersonMapper.update(isNull(), any())).thenReturn(0);

        withLoggedInUser(() -> service.updateProfile(request(2)));

        ArgumentCaptor<LambdaUpdateWrapper<LedgerPerson>> captor = personUpdateCaptor();
        verify(ledgerPersonMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<LedgerPerson> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("id"));
        assertTrue(wrapper.getSqlSegment().contains("linked_user_id"));
        assertTrue(wrapper.getSqlSegment().contains("deleted_at IS NULL"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(8L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void unlinkCommittedWhileProfileWaitsOnLedgerIsSeenByCurrentPeopleRead() {
        when(ledgerPersonMapper.selectObjs(any())).thenReturn(List.of(11L));
        when(ledgerMapper.selectList(any())).thenReturn(List.of(ledger(11L)));
        when(userAccountMapper.selectOne(any())).thenReturn(user(2));
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        when(ledgerPersonMapper.selectList(any())).thenReturn(List.of());

        withLoggedInUser(() -> service.updateProfile(request(2)));

        InOrder order = inOrder(ledgerMapper, userAccountMapper, ledgerPersonMapper);
        order.verify(ledgerMapper).selectList(any());
        order.verify(userAccountMapper).selectOne(any());
        order.verify(userAccountMapper).update(isNull(), any());
        ArgumentCaptor<Wrapper<LedgerPerson>> currentPeople = personWrapperCaptor();
        order.verify(ledgerPersonMapper).selectList(currentPeople.capture());
        assertCurrentPeopleLock(currentPeople.getValue());
        verify(ledgerPersonMapper, never()).update(isNull(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void profileConflictSnapshotHasOnlySafeFields() {
        Set<String> fields = Set.of("uuid", "nickname", "avatar", "version");
        Class<?> snapshotType;
        try {
            snapshotType = Class.forName("com.simon.ledger.dto.resp.ProfileConflictSnapshotResp");
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Profile conflict snapshot DTO is required", exception);
        }
        assertEquals(fields, Set.of(snapshotType.getDeclaredFields()).stream()
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet()));
        assertFalse(fields.contains("email"));
        assertFalse(fields.contains("phone"));
        assertFalse(fields.contains("status"));
        assertFalse(fields.contains("passwordHash"));
        assertFalse(fields.contains("token"));
        assertFalse(fields.contains("id"));
    }

    @Test
    void profileUpdateRemainsTransactionalForAllExceptions() throws Exception {
        Method method = AuthServiceImpl.class.getMethod("updateProfile", AuthProfileUpdateReq.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertEquals(Set.of(Exception.class), Set.of(transactional.rollbackFor()));
    }

    private void verifyUserUpdateWrapper(int expectedVersion) {
        ArgumentCaptor<LambdaUpdateWrapper<UserAccount>> captor = lambdaUpdateCaptor();
        verify(userAccountMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<UserAccount> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("id"));
        assertTrue(wrapper.getSqlSegment().contains("version"));
        assertTrue(wrapper.getSqlSegment().contains("deleted_at IS NULL"));
        assertTrue(wrapper.getSqlSet().contains("nickname"));
        assertTrue(wrapper.getSqlSet().contains("avatar"));
        assertTrue(wrapper.getSqlSet().contains("version"));
        assertTrue(wrapper.getSqlSet().contains("updated_at"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(expectedVersion));
    }

    private void verifyPersonSyncWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper<LedgerPerson>> captor = personUpdateCaptor();
        verify(ledgerPersonMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<LedgerPerson> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("id"));
        assertTrue(wrapper.getSqlSegment().contains("version"));
        assertTrue(wrapper.getSqlSegment().contains("deleted_at IS NULL"));
        assertTrue(wrapper.getSqlSet().contains("name"));
        assertTrue(wrapper.getSqlSet().contains("avatar"));
        assertTrue(wrapper.getSqlSet().contains("updated_at"));
        assertTrue(wrapper.getSqlSet().contains("version = version + 1"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(9));
        verify(ledgerPersonMapper, never()).updateById(any(LedgerPerson.class));
    }

    private void assertDiscoveryRead(Wrapper<LedgerPerson> read) {
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read;
        String sql = wrapper.getSqlSegment();
        assertTrue(wrapper.getSqlSelect().contains("DISTINCT ledger_id"));
        assertTrue(sql.contains("linked_user_id"));
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertTrue(sql.contains("ORDER BY ledger_id ASC"));
        assertFalse(sql.contains("FOR UPDATE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    private void assertLedgerNamespaceLock(Wrapper<Ledger> read, Long... ledgerIds) {
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read;
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("id IN"));
        assertTrue(sql.contains("ORDER BY id ASC"));
        assertTrue(sql.contains("FOR UPDATE"));
        assertFalse(sql.contains("deleted_at"));
        for (Long ledgerId : ledgerIds) {
            assertTrue(wrapper.getParamNameValuePairs().containsValue(ledgerId));
        }
    }

    private void assertCurrentUserLock(Wrapper<UserAccount> read) {
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read;
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("id"));
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertTrue(sql.contains("FOR UPDATE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    private void assertCurrentPeopleLock(Wrapper<LedgerPerson> read) {
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) read;
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("linked_user_id"));
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertTrue(sql.contains("ORDER BY id ASC"));
        assertTrue(sql.contains("FOR UPDATE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    private void assertProfileConflict(VersionConflictException exception, int submittedVersion,
                                       int remoteVersion, UserAccount remote) {
        ConflictResp conflict = assertInstanceOf(ConflictResp.class, exception.getData());
        assertEquals("profile", conflict.getEntityType());
        assertEquals(remote.getUuid(), conflict.getEntityUuid());
        assertEquals(submittedVersion, conflict.getSubmittedVersion());
        assertEquals(remoteVersion, conflict.getRemoteVersion());
        assertEquals(false, conflict.getRemoteDeleted());
        Object snapshot = conflict.getRemoteSnapshot();
        assertEquals("com.simon.ledger.dto.resp.ProfileConflictSnapshotResp", snapshot.getClass().getName());
        assertEquals(remote.getUuid(), ReflectionTestUtils.getField(snapshot, "uuid"));
        assertEquals(remote.getNickname(), ReflectionTestUtils.getField(snapshot, "nickname"));
        assertEquals(remote.getAvatar(), ReflectionTestUtils.getField(snapshot, "avatar"));
        assertEquals(remoteVersion, ReflectionTestUtils.getField(snapshot, "version"));
    }

    private AuthUserResp withLoggedInUser(ServiceCall call) {
        try (MockedStatic<StpUtil> stpUtil = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            return call.call();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<UserAccount>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<UserAccount>> lambdaUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
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
    private ArgumentCaptor<Wrapper<Ledger>> ledgerWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private UserAccount user(int version) {
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setUuid("user-uuid");
        user.setEmail("secret@example.test");
        user.setPhone("13800000000");
        user.setPasswordHash("secret-hash");
        user.setNickname("old nickname");
        user.setAvatar("https://example.test/old.png");
        user.setStatus(1);
        user.setVersion(version);
        return user;
    }

    private LedgerPerson person() {
        return person(8L, "person-uuid", 11L);
    }

    private LedgerPerson person(Long id, String uuid, Long ledgerId) {
        LedgerPerson person = new LedgerPerson();
        person.setId(id);
        person.setUuid(uuid);
        person.setLedgerId(ledgerId);
        person.setLinkedUserId(7L);
        person.setName("old nickname");
        person.setAvatar("https://example.test/old.png");
        person.setVersion(9);
        return person;
    }

    private Ledger ledger(Long id) {
        Ledger ledger = new Ledger();
        ledger.setId(id);
        ledger.setUuid("ledger-" + id);
        return ledger;
    }

    private AuthProfileUpdateReq request(int version) {
        AuthProfileUpdateReq request = new AuthProfileUpdateReq();
        request.setVersion(version);
        request.setNickname(" new nickname ");
        request.setAvatar(" https://example.test/new.png ");
        return request;
    }

    @FunctionalInterface
    private interface ServiceCall {
        AuthUserResp call();
    }
}

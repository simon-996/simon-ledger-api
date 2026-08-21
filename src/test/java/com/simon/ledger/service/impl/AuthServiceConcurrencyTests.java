package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.AuthProfileUpdateReq;
import com.simon.ledger.dto.resp.AuthUserResp;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceConcurrencyTests {

    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private LedgerPersonMapper ledgerPersonMapper;
    @Mock
    private ChangeLogService changeLogService;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(UserAccount.class);
        initializeLambdaMetadata(LedgerPerson.class);
        service = new AuthServiceImpl(ledgerPersonMapper, changeLogService);
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
        when(userAccountMapper.selectById(7L)).thenReturn(user);
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
    void initialStaleVersionThrowsSafeConflictWithoutAttemptingUpdate() {
        UserAccount user = user(3);
        when(userAccountMapper.selectById(7L)).thenReturn(user);

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
        when(userAccountMapper.selectById(7L)).thenReturn(initial);
        when(userAccountMapper.update(isNull(), any())).thenReturn(0);
        when(userAccountMapper.selectOne(any())).thenReturn(remote);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));

        assertProfileConflict(exception, 2, 4, remote);
        ArgumentCaptor<Wrapper<UserAccount>> reload = wrapperCaptor();
        verify(userAccountMapper).selectOne(reload.capture());
        assertTrue(reload.getValue().getSqlSegment().contains("FOR UPDATE"));
    }

    @Test
    void disabledAndMissingAccountsKeepExistingAuthorizationBehavior() {
        UserAccount disabled = user(2);
        disabled.setStatus(2);
        when(userAccountMapper.selectById(7L)).thenReturn(disabled);
        BusinessException disabledException = assertThrows(BusinessException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));
        assertEquals(ErrorCode.FORBIDDEN, disabledException.getErrorCode());

        when(userAccountMapper.selectById(7L)).thenReturn(null);
        BusinessException missingException = assertThrows(BusinessException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));
        assertEquals(ErrorCode.UNAUTHORIZED, missingException.getErrorCode());

        UserAccount deleted = user(2);
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(userAccountMapper.selectById(7L)).thenReturn(deleted);
        BusinessException deletedException = assertThrows(BusinessException.class,
                () -> withLoggedInUser(() -> service.updateProfile(request(2))));
        assertEquals(ErrorCode.UNAUTHORIZED, deletedException.getErrorCode());
    }

    @Test
    void unlinkedPersonSelectedBeforeRaceIsGuardedAndNotLogged() {
        when(userAccountMapper.selectById(7L)).thenReturn(user(2));
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        when(ledgerPersonMapper.selectList(any())).thenReturn(List.of(person()));
        // The selected row belonged to this profile, but a concurrent transaction unlinked it before this update.
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
        assertTrue(wrapper.getSqlSegment().contains("deleted_at IS NULL"));
        assertTrue(wrapper.getSqlSet().contains("name"));
        assertTrue(wrapper.getSqlSet().contains("avatar"));
        assertTrue(wrapper.getSqlSet().contains("updated_at"));
        assertTrue(wrapper.getSqlSet().contains("version = version + 1"));
        verify(ledgerPersonMapper, never()).updateById(any(LedgerPerson.class));
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
        LedgerPerson person = new LedgerPerson();
        person.setId(8L);
        person.setUuid("person-uuid");
        person.setLedgerId(11L);
        person.setLinkedUserId(7L);
        person.setName("old nickname");
        person.setAvatar("https://example.test/old.png");
        person.setVersion(9);
        return person;
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

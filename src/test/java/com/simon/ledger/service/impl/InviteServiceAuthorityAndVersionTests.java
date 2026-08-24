package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.dto.req.InviteCreateReq;
import com.simon.ledger.dto.req.InviteRegenerateReq;
import com.simon.ledger.dto.resp.InviteResp;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerInvite;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerInviteMapper;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteServiceAuthorityAndVersionTests {

    @Mock private LedgerInviteMapper inviteMapper;
    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper memberMapper;
    @Mock private UserAccountMapper userMapper;
    @Mock private ChangeLogService changeLogService;

    private InviteServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(LedgerInvite.class);
        initializeLambdaMetadata(Ledger.class);
        initializeLambdaMetadata(LedgerMember.class);
        initializeLambdaMetadata(UserAccount.class);
        service = new InviteServiceImpl(ledgerMapper, memberMapper, userMapper, changeLogService);
        ReflectionTestUtils.setField(service, "baseMapper", inviteMapper);
    }

    @Test
    void ownerCanCreateAdminInviteUsingLockedCurrentAuthority() {
        stubInviteCreation(LedgerRoles.OWNER);

        InviteResp response = loggedIn(() -> service.create("ledger-uuid", createReq(LedgerRoles.ADMIN)));

        assertEquals(LedgerRoles.ADMIN, response.getRole());
        ArgumentCaptor<LedgerInvite> inserted = ArgumentCaptor.forClass(LedgerInvite.class);
        verify(inviteMapper).insert(inserted.capture());
        assertEquals(LedgerRoles.ADMIN, inserted.getValue().getRole());
        assertEquals(7L, inserted.getValue().getCreatedByUserId());
        assertLockedActiveOperatorRead();
    }

    @Test
    void adminCanCreateEditorInviteUsingLockedCurrentAuthority() {
        stubInviteCreation(LedgerRoles.ADMIN);

        InviteResp response = loggedIn(() -> service.create("ledger-uuid", createReq(LedgerRoles.EDITOR)));

        assertEquals(LedgerRoles.EDITOR, response.getRole());
        verify(inviteMapper).insert(any(LedgerInvite.class));
        assertLockedActiveOperatorRead();
    }

    @Test
    void adminCanRegenerateViewerInviteUsingLockedCurrentAuthority() {
        stubInviteCreation(LedgerRoles.ADMIN);

        InviteResp response = loggedIn(() -> service.regenerate("ledger-uuid", regenerateReq(LedgerRoles.VIEWER)));

        assertEquals(LedgerRoles.VIEWER, response.getRole());
        verify(inviteMapper).insert(any(LedgerInvite.class));
        assertLockedActiveOperatorRead();
    }

    @Test
    void adminCannotCreateAdminInviteBeforeAnyInviteMutation() {
        stubLedgerAndOperator(LedgerRoles.ADMIN);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.create("ledger-uuid", createReq(LedgerRoles.ADMIN))));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        assertNull(exception.getData());
        assertLockedActiveOperatorRead();
        verify(inviteMapper, never()).update(any(), any());
        verify(inviteMapper, never()).insert(any(LedgerInvite.class));
    }

    @Test
    void adminCannotRegenerateAdminInviteBeforeDisablingCurrentInvite() {
        stubLedgerAndOperator(LedgerRoles.ADMIN);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.regenerate("ledger-uuid", regenerateReq(LedgerRoles.ADMIN))));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        assertNull(exception.getData());
        assertLockedActiveOperatorRead();
        verify(inviteMapper, never()).update(any(), any());
        verify(inviteMapper, never()).insert(any(LedgerInvite.class));
    }

    @Test
    void legacyAdminInviteCreatedByNonOwnerIsForbiddenBeforeMemberOrUsageMutation() {
        LedgerInvite invite = invite(LedgerRoles.ADMIN, 9L);
        when(inviteMapper.selectOne(any())).thenReturn(invite);
        when(ledgerMapper.selectById(11L)).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(member(31L, "creator-member", 9L, LedgerRoles.ADMIN, 1, null));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.join("INVITE01")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        assertNull(exception.getData());
        verify(memberMapper, never()).insert(any(LedgerMember.class));
        verify(memberMapper, never()).update(any(), any());
        verify(memberMapper, never()).updateById(any(LedgerMember.class));
        verify(inviteMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void invalidLegacyInviteRoleIsRejectedBeforeMemberOrUsageMutation() {
        LedgerInvite invite = invite("invalid", 7L);
        when(inviteMapper.selectOne(any())).thenReturn(invite);
        when(ledgerMapper.selectById(11L)).thenReturn(ledger());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.join("INVITE01")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verify(memberMapper, never()).selectOne(any());
        verify(memberMapper, never()).insert(any(LedgerMember.class));
        verify(memberMapper, never()).update(any(), any());
        verify(inviteMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deletedExistingMemberIsRestoredWithAtomicVersionedUpdate() {
        assertAtomicExistingMemberRestore(1, LocalDateTime.now());
    }

    @Test
    void disabledExistingMemberIsRestoredWithAtomicVersionedUpdate() {
        assertAtomicExistingMemberRestore(0, null);
    }

    @Test
    void zeroRowExistingMemberRestoreReloadsLatestForUpdateAndReturnsSafeConflict() {
        LedgerMember observed = restorableMember(1, 4, LocalDateTime.now());
        LedgerMember latest = restorableMember(1, 8, null);
        latest.setRole(LedgerRoles.EDITOR);
        UserAccount latestUser = user(8L);
        stubExistingMemberJoin(observed);
        when(memberMapper.update(isNull(), any())).thenReturn(0);
        when(memberMapper.selectOne(any())).thenReturn(observed, latest);
        when(userMapper.selectById(8L)).thenReturn(latestUser);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.join("INVITE01")));

        assertEquals(ErrorCode.CONFLICT, exception.getErrorCode());
        ConflictResp conflict = (ConflictResp) exception.getData();
        assertEquals("member", conflict.getEntityType());
        assertEquals("existing-member", conflict.getEntityUuid());
        assertEquals(4, conflict.getSubmittedVersion());
        assertEquals(8, conflict.getRemoteVersion());
        assertFalse(conflict.getRemoteDeleted());
        MemberResp snapshot = (MemberResp) conflict.getRemoteSnapshot();
        assertEquals("user-uuid", snapshot.getUserUuid());
        assertEquals("Nickname", snapshot.getNickname());
        assertEquals("avatar", snapshot.getAvatar());
        assertEquals(LedgerRoles.EDITOR, snapshot.getRole());
        assertEquals(1, snapshot.getStatus());
        assertEquals(8, snapshot.getVersion());
        ArgumentCaptor<Wrapper<LedgerMember>> reads = memberWrapperCaptor();
        verify(memberMapper, org.mockito.Mockito.times(2)).selectOne(reads.capture());
        AbstractWrapper<?, ?, ?> latestRead = (AbstractWrapper<?, ?, ?>) reads.getAllValues().get(1);
        assertTrue(latestRead.getSqlSegment().contains("id"));
        assertTrue(latestRead.getSqlSegment().contains("FOR UPDATE"));
        assertTrue(latestRead.getParamNameValuePairs().containsValue(22L));
        verify(memberMapper, never()).updateById(any(LedgerMember.class));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        verify(inviteMapper, never()).update(any(), any());
    }

    @Test
    void zeroRowExistingMemberRestoreWithMissingLatestReturnsDataSafeConflict() {
        LedgerMember observed = restorableMember(0, 4, null);
        stubExistingMemberJoin(observed);
        when(memberMapper.update(isNull(), any())).thenReturn(0);
        when(memberMapper.selectOne(any())).thenReturn(observed).thenReturn(null);

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.join("INVITE01")));

        assertEquals(ErrorCode.CONFLICT, exception.getErrorCode());
        ConflictResp conflict = (ConflictResp) exception.getData();
        assertEquals("member", conflict.getEntityType());
        assertEquals("existing-member", conflict.getEntityUuid());
        assertEquals(4, conflict.getSubmittedVersion());
        assertNull(conflict.getRemoteVersion());
        assertTrue(conflict.getRemoteDeleted());
        assertNull(conflict.getRemoteSnapshot());
        verify(memberMapper, never()).updateById(any(LedgerMember.class));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
        verify(inviteMapper, never()).update(any(), any());
    }

    private void assertAtomicExistingMemberRestore(int status, LocalDateTime deletedAt) {
        LedgerMember observed = restorableMember(status, 4, deletedAt);
        stubExistingMemberJoin(observed);
        when(memberMapper.update(isNull(), any())).thenReturn(1);
        when(inviteMapper.update(isNull(), any())).thenReturn(1);
        when(memberMapper.selectList(any())).thenReturn(List.of());

        InviteResp response = loggedIn(() -> service.join("INVITE01"));

        assertEquals(LedgerRoles.VIEWER, response.getRole());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> captor = memberUpdateCaptor();
        verify(memberMapper).update(isNull(), captor.capture());
        assertAtomicRestoreWrapper(captor.getValue(), 4, 5);
        assertEquals(LedgerRoles.VIEWER, observed.getRole());
        assertEquals(1, observed.getStatus());
        assertEquals(5, observed.getVersion());
        assertNull(observed.getDeletedAt());
        verify(memberMapper, never()).updateById(any(LedgerMember.class));
        org.mockito.InOrder order = inOrder(memberMapper, changeLogService, inviteMapper);
        order.verify(memberMapper).update(isNull(), any());
        order.verify(changeLogService).record(11L, "member", "existing-member", "update", 7L);
        order.verify(inviteMapper).update(isNull(), any());
    }

    private void stubExistingMemberJoin(LedgerMember existing) {
        when(inviteMapper.selectOne(any())).thenReturn(invite(LedgerRoles.VIEWER, 9L));
        when(ledgerMapper.selectById(11L)).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(existing);
    }

    private void assertAtomicRestoreWrapper(LambdaUpdateWrapper<LedgerMember> wrapper,
                                            int submittedVersion, int nextVersion) {
        String where = wrapper.getSqlSegment();
        String set = wrapper.getSqlSet();
        assertTrue(where.matches("(?s).*\\bid\\b\\s*=.*"));
        assertTrue(where.matches("(?s).*\\bversion\\b\\s*=.*"));
        assertTrue(where.matches("(?s).*\\bstatus\\b\\s*<>.*"));
        assertTrue(where.contains("deleted_at IS NOT NULL"));
        assertTrue(where.contains("OR"));
        assertTrue(set.contains("role"));
        assertTrue(set.contains("status"));
        assertTrue(set.contains("joined_at"));
        assertTrue(set.contains("deleted_at"));
        assertTrue(set.contains("updated_at"));
        assertTrue(set.contains("version"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(22L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(submittedVersion));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(nextVersion));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1));
    }

    private void stubInviteCreation(String operatorRole) {
        stubLedgerAndOperator(operatorRole);
        when(inviteMapper.selectCount(any())).thenReturn(0L);
        when(inviteMapper.insert(any(LedgerInvite.class))).thenReturn(1);
        when(memberMapper.selectList(any())).thenReturn(List.of());
    }

    private void stubLedgerAndOperator(String operatorRole) {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(
                member(21L, "operator-member", 7L, operatorRole, 1, null));
    }

    private void assertLockedActiveOperatorRead() {
        ArgumentCaptor<Wrapper<LedgerMember>> captor = memberWrapperCaptor();
        verify(memberMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("ledger_id"));
        assertTrue(sql.contains("user_id"));
        assertTrue(sql.contains("status"));
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
        ledger.setName("Ledger");
        ledger.setBaseCurrencyCode("CNY");
        ledger.setOwnerUserId(7L);
        return ledger;
    }

    private LedgerMember member(Long id, String uuid, Long userId, String role, int version,
                                LocalDateTime deletedAt) {
        LedgerMember member = new LedgerMember();
        member.setId(id);
        member.setUuid(uuid);
        member.setLedgerId(11L);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(1);
        member.setVersion(version);
        member.setJoinedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        member.setDeletedAt(deletedAt);
        return member;
    }

    private LedgerMember restorableMember(int status, int version, LocalDateTime deletedAt) {
        LedgerMember member = member(22L, "existing-member", 8L, LedgerRoles.EDITOR, version, deletedAt);
        member.setStatus(status);
        return member;
    }

    private UserAccount user(Long id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUuid("user-uuid");
        user.setNickname("Nickname");
        user.setAvatar("avatar");
        return user;
    }

    private LedgerInvite invite(String role, Long createdByUserId) {
        LedgerInvite invite = new LedgerInvite();
        invite.setId(41L);
        invite.setUuid("invite-uuid");
        invite.setLedgerId(11L);
        invite.setCode("INVITE01");
        invite.setRole(role);
        invite.setCreatedByUserId(createdByUserId);
        invite.setMaxUses(5);
        invite.setUsedCount(1);
        invite.setExpiresAt(LocalDateTime.now().plusDays(1));
        invite.setCreatedAt(LocalDateTime.now().minusDays(1));
        return invite;
    }

    private InviteCreateReq createReq(String role) {
        InviteCreateReq req = new InviteCreateReq();
        req.setRole(role);
        req.setMaxUses(5);
        req.setExpiresAt(LocalDateTime.now().plusDays(1));
        return req;
    }

    private InviteRegenerateReq regenerateReq(String role) {
        InviteRegenerateReq req = new InviteRegenerateReq();
        req.setRole(role);
        req.setDays(3);
        req.setMaxUses(5);
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
    private ArgumentCaptor<Wrapper<LedgerMember>> memberWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> memberUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @FunctionalInterface
    private interface ServiceCall<T> {
        T call();
    }
}

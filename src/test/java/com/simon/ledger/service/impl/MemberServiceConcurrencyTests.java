package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.LedgerRoles;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.common.exception.VersionConflictException;
import com.simon.ledger.controller.MemberController;
import com.simon.ledger.dto.req.MemberRoleUpdateReq;
import com.simon.ledger.dto.req.VersionDeleteReq;
import com.simon.ledger.dto.resp.ConflictResp;
import com.simon.ledger.dto.resp.MemberResp;
import com.simon.ledger.dto.resp.VersionMutationResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.ChangeLogService;
import com.simon.ledger.service.IdempotencyService;
import com.simon.ledger.service.MemberService;
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

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MemberServiceConcurrencyTests {

    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper memberMapper;
    @Mock private UserAccountMapper userMapper;
    @Mock private ChangeLogService changeLogService;

    private MemberServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeLambdaMetadata(Ledger.class);
        initializeLambdaMetadata(LedgerMember.class);
        initializeLambdaMetadata(UserAccount.class);
        service = new MemberServiceImpl(ledgerMapper, userMapper, changeLogService);
        ReflectionTestUtils.setField(service, "baseMapper", memberMapper);
    }

    @Test
    void ownerPromotesEditorWithAtomicVersionPredicateAndReturnsSafeIncrementedResponse() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember target = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, null);
        stubLedgerOperatorTarget(owner, target);
        when(memberMapper.update(isNull(), any())).thenReturn(1);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        MemberResp response = loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid",
                roleReq(4, " ADMIN ")));

        assertEquals(LedgerRoles.ADMIN, response.getRole());
        assertEquals(5, response.getVersion());
        assertEquals("user-uuid", response.getUserUuid());
        assertEquals("Test User", response.getNickname());
        assertEquals("avatar.png", response.getAvatar());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> captor = memberUpdateCaptor();
        verify(memberMapper).update(isNull(), captor.capture());
        assertAtomicWrapper(captor.getValue(), "deleted_at IS NULL", 4, 5);
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
        assertTrue(captor.getValue().getSqlSet().contains("role"));
        assertTrue(captor.getValue().getSqlSet().contains("updated_at"));
        verify(memberMapper, never()).updateById(any(LedgerMember.class));
        verify(changeLogService).record(11L, "member", "member-uuid", "update", 7L);
    }

    @Test
    void staleAndRemoteDeletedUpdatesReturnSafeConflictWithoutLogging() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember stale = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 6, null);
        stubLedgerOperatorTarget(owner, stale);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        VersionConflictException staleException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));
        assertMemberConflict(staleException, 4, 6, false, LedgerRoles.EDITOR);

        LedgerMember removed = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 7, LocalDateTime.now());
        when(memberMapper.selectOne(any())).thenReturn(owner, removed);
        VersionConflictException removedException = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(7, "viewer"))));
        assertMemberConflict(removedException, 7, 7, true, LedgerRoles.EDITOR);

        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroReloadsLatestMemberForUpdateAndDoesNotLog() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember initial = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, null);
        LedgerMember latest = member(22L, "member-uuid", 8L, LedgerRoles.VIEWER, 8, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(owner, initial, latest);
        when(memberMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertMemberConflict(exception, 4, 8, true, LedgerRoles.VIEWER);
        ArgumentCaptor<Wrapper<LedgerMember>> reload = memberWrapperCaptor();
        verify(memberMapper, times(3)).selectOne(reload.capture());
        assertTrue(reload.getAllValues().get(2).getSqlSegment().contains("FOR UPDATE"));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroReloadsLatestMemberForRemoveAndDoesNotLog() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember initial = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, null);
        LedgerMember latest = member(22L, "member-uuid", 8L, LedgerRoles.VIEWER, 8, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(owner, initial, latest);
        when(memberMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));

        assertMemberConflict(exception, 4, 8, true, LedgerRoles.VIEWER);
        ArgumentCaptor<Wrapper<LedgerMember>> reload = memberWrapperCaptor();
        verify(memberMapper, times(3)).selectOne(reload.capture());
        assertTrue(reload.getAllValues().get(2).getSqlSegment().contains("FOR UPDATE"));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroReloadsLatestMemberForRestoreAndDoesNotLog() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember initial = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, LocalDateTime.now());
        LedgerMember latest = member(22L, "member-uuid", 8L, LedgerRoles.VIEWER, 9, null);
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(owner, initial, latest);
        when(memberMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertMemberConflict(exception, 4, 9, false, LedgerRoles.VIEWER);
        ArgumentCaptor<Wrapper<LedgerMember>> reload = memberWrapperCaptor();
        verify(memberMapper, times(3)).selectOne(reload.capture());
        assertTrue(reload.getAllValues().get(2).getSqlSegment().contains("FOR UPDATE"));
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroUpdateReauthorizesLatestAdminBeforeReturningConflict() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember initial = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, null);
        LedgerMember latestAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 8, null);
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(admin, initial, latestAdmin);
        when(memberMapper.update(isNull(), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertForbiddenWithoutConflictData(exception);
        assertLatestReloadUsedForUpdate();
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroRemoveReauthorizesLatestOwnerBeforeReturningConflict() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember initial = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, null);
        LedgerMember latestOwner = member(22L, "member-uuid", 8L, LedgerRoles.OWNER, 8, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(admin, initial, latestOwner);
        when(memberMapper.update(isNull(), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));

        assertForbiddenWithoutConflictData(exception);
        assertLatestReloadUsedForUpdate();
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void affectedZeroRestoreReauthorizesLatestAdminBeforeReturningConflict() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember initial = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, LocalDateTime.now());
        LedgerMember latestAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 8, LocalDateTime.now());
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(admin, initial, latestAdmin);
        when(memberMapper.update(isNull(), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertForbiddenWithoutConflictData(exception);
        assertLatestReloadUsedForUpdate();
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void ownerCanRemoveAdminWithVersionedSoftDelete() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember target = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 4, null);
        stubLedgerOperatorTarget(owner, target);
        when(memberMapper.update(isNull(), any())).thenReturn(1);

        VersionMutationResp response = loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4)));

        assertEquals("member-uuid", response.getUuid());
        assertEquals(5, response.getVersion());
        assertTrue(response.getDeleted());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> captor = memberUpdateCaptor();
        verify(memberMapper).update(isNull(), captor.capture());
        assertAtomicWrapper(captor.getValue(), "deleted_at IS NULL", 4, 5);
        assertTrue(captor.getValue().getSqlSet().contains("deleted_at"));
        verify(changeLogService).record(11L, "member", "member-uuid", "delete", 7L);
    }

    @Test
    void ownerRestoresDeletedEditorAsAdminWithIncrementedVersion() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember target = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, LocalDateTime.now());
        stubLedgerOperatorTarget(owner, target);
        when(memberMapper.update(isNull(), any())).thenReturn(1);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        MemberResp response = loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "admin")));

        assertEquals(LedgerRoles.ADMIN, response.getRole());
        assertEquals(5, response.getVersion());
        ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> captor = memberUpdateCaptor();
        verify(memberMapper).update(isNull(), captor.capture());
        assertAtomicWrapper(captor.getValue(), "deleted_at IS NOT NULL", 4, 5);
        assertTrue(captor.getValue().getSqlSet().contains("role"));
        assertTrue(captor.getValue().getSqlSet().contains("deleted_at"));
        verify(changeLogService).record(11L, "member", "member-uuid", "update", 7L);
    }

    @Test
    void activeRestoreStaleRemoveAndRemoteDeletedRemoveConflictWithoutLogging() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember active = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 4, null);
        stubLedgerOperatorTarget(owner, active);
        when(userMapper.selectById(8L)).thenReturn(user(8L));
        VersionConflictException activeRestore = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));
        assertMemberConflict(activeRestore, 4, 4, false, LedgerRoles.EDITOR);

        LedgerMember stale = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 6, null);
        when(memberMapper.selectOne(any())).thenReturn(owner, stale);
        VersionConflictException staleRemove = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));
        assertMemberConflict(staleRemove, 4, 6, false, LedgerRoles.EDITOR);

        LedgerMember deleted = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 7, LocalDateTime.now());
        when(memberMapper.selectOne(any())).thenReturn(owner, deleted);
        VersionConflictException deletedRemove = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(7))));
        assertMemberConflict(deletedRemove, 7, 7, true, LedgerRoles.EDITOR);
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void staleDeletedRestoreReturnsRemoteDeletedConflictBeforeWriting() {
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember deleted = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 7, LocalDateTime.now());
        stubLedgerOperatorTarget(owner, deleted);
        when(userMapper.selectById(8L)).thenReturn(user(8L));

        VersionConflictException exception = assertThrows(VersionConflictException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertMemberConflict(exception, 4, 7, true, LedgerRoles.EDITOR);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void adminStaleUpdateOfCurrentAdminIsForbiddenWithoutConflictData() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember targetAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 9, null);
        stubLedgerOperatorTarget(admin, targetAdmin);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "editor"))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void adminStaleRemoveOfActiveAdminIsForbiddenWithoutConflictData() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember activeAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 9, null);
        stubLedgerOperatorTarget(admin, activeAdmin);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void adminRemoveOfDeletedAdminIsForbiddenWithoutConflictData() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember deletedAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 10, LocalDateTime.now());
        stubLedgerOperatorTarget(admin, deletedAdmin);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void adminStaleRestoreAsAdminIsForbiddenWithoutConflictData() {
        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember deletedEditor = member(22L, "member-uuid", 8L, LedgerRoles.EDITOR, 9, LocalDateTime.now());
        stubLedgerOperatorTarget(admin, deletedEditor);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "admin"))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void staleOwnerTargetUpdateIsForbiddenWithoutConflictData() {
        LedgerMember ownerOperator = member(21L, "owner-operator", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember staleOwner = member(22L, "member-uuid", 8L, LedgerRoles.OWNER, 9, null);
        stubLedgerOperatorTarget(ownerOperator, staleOwner);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deletedOwnerTargetRemoveIsForbiddenWithoutConflictData() {
        LedgerMember ownerOperator = member(21L, "owner-operator", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember deletedOwner = member(22L, "member-uuid", 8L, LedgerRoles.OWNER, 10, LocalDateTime.now());
        stubLedgerOperatorTarget(ownerOperator, deletedOwner);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deletedOwnerTargetRestoreIsForbiddenWithoutConflictData() {
        LedgerMember ownerOperator = member(21L, "owner-operator", 7L, LedgerRoles.OWNER, 3, null);
        LedgerMember deletedOwner = member(22L, "member-uuid", 8L, LedgerRoles.OWNER, 10, LocalDateTime.now());
        stubLedgerOperatorTarget(ownerOperator, deletedOwner);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));

        assertForbiddenWithoutConflictData(exception);
        verify(memberMapper, never()).update(any(), any());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void ownerIsImmutableAndAdminCannotPromoteOrManageAdmins() {
        assertForbidden(LedgerRoles.OWNER, LedgerRoles.OWNER, "viewer");
        assertForbidden(LedgerRoles.ADMIN, LedgerRoles.EDITOR, "admin");
        assertForbidden(LedgerRoles.ADMIN, LedgerRoles.ADMIN, "editor");

        LedgerMember admin = member(21L, "admin-member", 7L, LedgerRoles.ADMIN, 3, null);
        LedgerMember targetAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 4, null);
        stubLedgerOperatorTarget(admin, targetAdmin);
        BusinessException removeAdmin = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRemove("ledger-uuid", "member-uuid", deleteReq(4))));
        assertEquals(ErrorCode.FORBIDDEN, removeAdmin.getErrorCode());

        LedgerMember deletedAdmin = member(22L, "member-uuid", 8L, LedgerRoles.ADMIN, 4, LocalDateTime.now());
        when(memberMapper.selectOne(any())).thenReturn(admin, deletedAdmin);
        BusinessException restoreAdmin = assertThrows(BusinessException.class,
                () -> loggedIn(() -> invokeRestore("ledger-uuid", "member-uuid", roleReq(4, "viewer"))));
        assertEquals(ErrorCode.FORBIDDEN, restoreAdmin.getErrorCode());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void editorViewerAndInactiveOperatorsCannotManageMembers() {
        for (String role : List.of(LedgerRoles.EDITOR, LedgerRoles.VIEWER)) {
            LedgerMember operator = member(21L, "operator", 7L, role, 3, null);
            LedgerMember target = member(22L, "member-uuid", 8L, LedgerRoles.VIEWER, 4, null);
            stubLedgerOperatorTarget(operator, target);
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "editor"))));
            assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        }

        when(memberMapper.selectOne(any())).thenReturn((LedgerMember) null);
        BusinessException inactive = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, "editor"))));
        assertEquals(ErrorCode.FORBIDDEN, inactive.getErrorCode());
        verify(changeLogService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void trueMissingTargetIsNotFoundAndListFiltersActiveNonDeletedMembers() {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        LedgerMember owner = member(21L, "owner-member", 7L, LedgerRoles.OWNER, 3, null);
        when(memberMapper.selectOne(any())).thenReturn(owner, (LedgerMember) null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "missing", roleReq(4, "viewer"))));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());

        when(memberMapper.selectOne(any())).thenReturn(owner);
        when(memberMapper.selectList(any())).thenReturn(List.of(owner));
        when(userMapper.selectList(any())).thenReturn(List.of(user(7L)));
        loggedIn(() -> service.list("ledger-uuid"));
        ArgumentCaptor<Wrapper<LedgerMember>> listQuery = memberWrapperCaptor();
        verify(memberMapper).selectList(listQuery.capture());
        assertTrue(listQuery.getValue().getSqlSegment().contains("status"));
        assertTrue(listQuery.getValue().getSqlSegment().contains("deleted_at IS NULL"));
    }

    @Test
    void deleteAndRestoreControllerContractsUseValidatedBodiesAndTypedIdempotency() throws Exception {
        VersionMutationResp removed = new VersionMutationResp("member-uuid", 5, true);
        MemberResp restored = new MemberResp();
        restored.setUuid("member-uuid");
        restored.setVersion(6);
        MemberService memberService = mock(MemberService.class);
        when(memberService.remove(eq("ledger-uuid"), eq("member-uuid"), any())).thenReturn(removed);
        when(memberService.restore(eq("ledger-uuid"), eq("member-uuid"), any())).thenReturn(restored);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(4);
            return supplier.get();
        });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemberController(memberService, idempotencyService)).build();

        mvc.perform(delete("/api/ledgers/ledger-uuid/members/member-uuid")
                        .header("Idempotency-Key", "delete-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("member-uuid"))
                .andExpect(jsonPath("$.data.version").value(5));
        verify(idempotencyService).execute(eq("delete-key"), eq("DELETE"),
                eq("/api/ledgers/ledger-uuid/members/member-uuid"), eq(VersionMutationResp.class), any());
        verify(idempotencyService, never()).executeVoid(any(), any(), any(), any());
        mvc.perform(delete("/api/ledgers/ledger-uuid/members/member-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/ledgers/ledger-uuid/members/member-uuid/restore")
                        .header("Idempotency-Key", "restore-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5,\"role\":\"viewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(6));
        verify(idempotencyService).execute(eq("restore-key"), eq("POST"),
                eq("/api/ledgers/ledger-uuid/members/member-uuid/restore"), eq(MemberResp.class), any());
    }

    @Test
    void writeMethodsRemainTransactionalForAllExceptionsAndExposeVersionedSignatures() throws Exception {
        assertTransactional("updateRole", MemberRoleUpdateReq.class);
        assertTransactional("remove", VersionDeleteReq.class);
        assertTransactional("restore", MemberRoleUpdateReq.class);
        assertEquals(VersionMutationResp.class,
                MemberService.class.getMethod("remove", String.class, String.class, VersionDeleteReq.class).getReturnType());
        assertEquals(MemberResp.class,
                MemberService.class.getMethod("restore", String.class, String.class, MemberRoleUpdateReq.class).getReturnType());
    }

    private void assertForbidden(String operatorRole, String targetRole, String newRole) {
        LedgerMember operator = member(21L, "operator", 7L, operatorRole, 3, null);
        LedgerMember target = member(22L, "member-uuid", 8L, targetRole, 4, null);
        stubLedgerOperatorTarget(operator, target);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> loggedIn(() -> service.updateRole("ledger-uuid", "member-uuid", roleReq(4, newRole))));
        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    private void assertForbiddenWithoutConflictData(BusinessException exception) {
        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        assertNull(exception.getData());
    }

    private void assertLatestReloadUsedForUpdate() {
        ArgumentCaptor<Wrapper<LedgerMember>> reload = memberWrapperCaptor();
        verify(memberMapper, times(3)).selectOne(reload.capture());
        assertTrue(reload.getAllValues().get(2).getSqlSegment().contains("FOR UPDATE"));
    }

    private void assertTransactional(String name, Class<?> requestType) throws Exception {
        Method method = MemberServiceImpl.class.getMethod(name, String.class, String.class, requestType);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertEquals(Set.of(Exception.class), Set.of(annotation.rollbackFor()));
    }

    private VersionMutationResp invokeRemove(String ledgerUuid, String memberUuid, VersionDeleteReq req) {
        return service.remove(ledgerUuid, memberUuid, req);
    }

    private MemberResp invokeRestore(String ledgerUuid, String memberUuid, MemberRoleUpdateReq req) {
        return service.restore(ledgerUuid, memberUuid, req);
    }

    private void assertAtomicWrapper(LambdaUpdateWrapper<?> wrapper, String deletedPredicate, int submitted, int next) {
        assertTrue(wrapper.getSqlSegment().contains("id"));
        assertTrue(wrapper.getSqlSegment().contains("version"));
        assertTrue(wrapper.getSqlSegment().contains(deletedPredicate));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(submitted));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(next));
        assertTrue(wrapper.getSqlSet().contains("version"));
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

    private void stubLedgerOperatorTarget(LedgerMember operator, LedgerMember target) {
        when(ledgerMapper.selectOne(any())).thenReturn(ledger());
        when(memberMapper.selectOne(any())).thenReturn(operator, target);
    }

    private Ledger ledger() {
        Ledger ledger = new Ledger();
        ledger.setId(11L);
        ledger.setUuid("ledger-uuid");
        return ledger;
    }

    private LedgerMember member(Long id, String uuid, Long userId, String role, int version, LocalDateTime deletedAt) {
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

    private UserAccount user(Long id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUuid("user-uuid");
        user.setNickname("Test User");
        user.setAvatar("avatar.png");
        return user;
    }

    private MemberRoleUpdateReq roleReq(int version, String role) {
        MemberRoleUpdateReq req = new MemberRoleUpdateReq();
        req.setVersion(version);
        req.setRole(role);
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
    private ArgumentCaptor<LambdaUpdateWrapper<LedgerMember>> memberUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
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

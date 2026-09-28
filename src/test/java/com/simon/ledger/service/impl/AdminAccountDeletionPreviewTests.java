package com.simon.ledger.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.AdminAccountDeletionPreviewResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.LedgerTransactionMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.AdminService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccountDeletionPreviewTests {

    @Mock private AdminService adminService;
    @Mock private UserAccountMapper userMapper;
    @Mock private LedgerMapper ledgerMapper;
    @Mock private LedgerMemberMapper memberMapper;
    @Mock private LedgerPersonMapper personMapper;
    @Mock private LedgerTransactionMapper transactionMapper;

    @BeforeEach
    void initializeTableMetadata() {
        for (Class<?> type : List.of(UserAccount.class, Ledger.class, LedgerMember.class,
                LedgerPerson.class, LedgerTransaction.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
            }
        }
    }

    @Test
    void previewClassifiesSoloSharedJoinedAndSoftDeletedOwnedLedgers() {
        UserAccount target = user(1, "target", "Target", 1);
        UserAccount successor = user(2, "successor", "Successor", 1);
        UserAccount disabled = user(3, "disabled", "Disabled", 2);
        UserAccount otherOwner = user(4, "other-owner", "Other", 1);
        Ledger solo = ledger(10, "solo", 1, null);
        Ledger shared = ledger(20, "shared", 1, null);
        Ledger joined = ledger(30, "joined", 4, null);
        Ledger deleted = ledger(40, "deleted", 1, LocalDateTime.of(2026, 9, 1, 0, 0));

        when(userMapper.selectOne(any())).thenReturn(target);
        when(memberMapper.selectList(any()))
                .thenReturn(List.of(member(101, 10, 1, 1, null), member(201, 20, 1, 1, null),
                        member(301, 30, 1, 1, null), member(401, 40, 1, 1, null)))
                .thenReturn(List.of(member(101, 10, 1, 1, null), member(201, 20, 1, 1, null),
                        member(202, 20, 2, 1, null), member(203, 20, 3, 1, null),
                        member(204, 20, 4, 1, LocalDateTime.of(2026, 9, 1, 0, 0)),
                        member(301, 30, 1, 1, null), member(302, 30, 4, 1, null),
                        member(401, 40, 1, 1, null), member(402, 40, 2, 1, null)));
        when(ledgerMapper.selectList(any())).thenReturn(List.of(solo, shared, deleted))
                .thenReturn(List.of(solo, shared, joined, deleted));
        when(userMapper.selectList(any())).thenReturn(List.of(successor, disabled, otherOwner));
        when(personMapper.selectCount(any())).thenReturn(2L, 3L, 4L, 5L);
        when(transactionMapper.selectCount(any())).thenReturn(6L, 7L, 8L, 9L);

        AdminAccountDeletionPreviewResp preview = service().preview("target");

        verify(adminService).me();
        assertEquals("target", preview.getUserUuid());
        assertEquals("Target", preview.getNickname());
        assertEquals(3, preview.getOwnedLedgers().size());
        assertEquals(1, preview.getJoinedLedgers().size());
        assertEquals("solo", preview.getOwnedLedgers().get(0).getUuid());
        assertEquals("DELETE", preview.getOwnedLedgers().get(0).getAction());
        assertEquals(1, preview.getOwnedLedgers().get(0).getMemberCount());
        assertEquals(1, preview.getOwnedLedgers().get(0).getDeletedMemberCount());
        assertEquals(2, preview.getOwnedLedgers().get(0).getDeletedPersonCount());
        assertEquals(6, preview.getOwnedLedgers().get(0).getDeletedTransactionCount());
        assertEquals("TRANSFER", preview.getOwnedLedgers().get(1).getAction());
        assertEquals(List.of("successor"), preview.getOwnedLedgers().get(1).getSuccessors()
                .stream().map(AdminAccountDeletionPreviewResp.Member::getUserUuid).toList());
        assertEquals("editor", preview.getOwnedLedgers().get(1).getSuccessors().getFirst().getRole());
        assertEquals(1, preview.getOwnedLedgers().get(1).getDeletedMemberCount());
        assertEquals(3, preview.getOwnedLedgers().get(1).getRetainedMemberCount());
        assertEquals(2, preview.getOwnedLedgers().get(1).getActiveMembers().size());
        assertEquals(3, preview.getOwnedLedgers().get(1).getRetainedPersonCount());
        assertEquals(7, preview.getOwnedLedgers().get(1).getRetainedTransactionCount());
        assertEquals("DETACH", preview.getJoinedLedgers().getFirst().getAction());
        assertEquals(1, preview.getJoinedLedgers().getFirst().getDeletedMemberCount());
        assertEquals(4, preview.getJoinedLedgers().getFirst().getRetainedPersonCount());
        assertEquals(8, preview.getJoinedLedgers().getFirst().getRetainedTransactionCount());
        assertEquals("DELETE", preview.getOwnedLedgers().get(2).getAction());
        assertTrue(preview.getOwnedLedgers().get(2).isDeleted());
        assertTrue(preview.getOwnedLedgers().get(2).getSuccessors().isEmpty());
        assertEquals(2, preview.getOwnedLedgers().get(2).getDeletedMemberCount());
        assertEquals(5, preview.getOwnedLedgers().get(2).getDeletedPersonCount());
        assertEquals(9, preview.getOwnedLedgers().get(2).getDeletedTransactionCount());
        assertEquals(1, preview.getSummary().getLedgersToTransfer());
        assertEquals(2, preview.getSummary().getLedgersToDelete());
        assertEquals(1, preview.getSummary().getOtherLedgers());
        assertEquals(7, preview.getSummary().getPeopleToKeep());
        assertEquals(7, preview.getSummary().getPeopleToDelete());
        assertEquals(15, preview.getSummary().getTransactionsToKeep());
        assertEquals(15, preview.getSummary().getTransactionsToDelete());
        assertTrue(preview.getFingerprint().matches("[0-9a-f]{64}"));
        assertFalse(preview.getFingerprint().contains("target"));
        verify(ledgerMapper).selectList(org.mockito.ArgumentMatchers.argThat(wrapper ->
                wrapper.getSqlSegment().contains("owner_user_id")
                        && !wrapper.getSqlSegment().contains("deleted_at")));
        verify(personMapper, times(4)).selectCount(org.mockito.ArgumentMatchers.argThat(wrapper ->
                !wrapper.getSqlSegment().contains("deleted_at")));
        verify(transactionMapper, times(4)).selectCount(org.mockito.ArgumentMatchers.argThat(wrapper ->
                !wrapper.getSqlSegment().contains("deleted_at")));
    }

    @Test
    void fingerprintIsStableForQueryOrderAndChangesWithMembershipVersion() {
        String original = fingerprintFor(false, 1);
        assertEquals(original, fingerprintFor(true, 1));
        assertNotEquals(original, fingerprintFor(false, 2));
    }

    @Test
    void fingerprintChangesWhenLinkedParticipantChangesWithoutChangingLedgerCountOrVersion() {
        UserAccount target = user(1, "target", "Target", 1);
        Ledger ledger = ledger(10, "shared", 1, null);
        LedgerMember member = member(101, 10, 1, 1, null);
        LedgerPerson before = new LedgerPerson();
        before.setId(501L);
        before.setUuid("person-501");
        before.setLedgerId(10L);
        before.setLinkedUserId(1L);
        before.setVersion(1);
        LedgerPerson after = new LedgerPerson();
        after.setId(501L);
        after.setUuid("person-501");
        after.setLedgerId(10L);
        after.setLinkedUserId(1L);
        after.setVersion(2);
        when(userMapper.selectOne(any())).thenReturn(target);
        when(memberMapper.selectList(any())).thenReturn(List.of(member));
        when(ledgerMapper.selectList(any())).thenReturn(List.of(ledger));
        when(personMapper.selectCount(any())).thenReturn(1L);
        when(transactionMapper.selectCount(any())).thenReturn(0L);
        when(personMapper.selectList(any())).thenReturn(List.of(before)).thenReturn(List.of(after));

        String first = service().preview("target").getFingerprint();
        String second = service().preview("target").getFingerprint();
        assertNotEquals(first, second);
    }

    @Test
    void missingAccountReturnsNotFoundAfterAdminValidation() {
        when(userMapper.selectOne(any())).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class, () -> service().preview("missing"));
        verify(adminService).me();
        verify(ledgerMapper, never()).selectList(any());
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void rejectsNonAdminBeforeReadingAccount() {
        when(adminService.me()).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "不是后台登录态"));
        BusinessException error = assertThrows(BusinessException.class, () -> service().preview("target"));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void fallsBackToPhoneWhenEmailIsBlank() {
        UserAccount target = user(1, "target", "Target", 1);
        target.setEmail("");
        target.setPhone("123456789");
        when(userMapper.selectOne(any())).thenReturn(target);
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(ledgerMapper.selectList(any())).thenReturn(List.of());

        assertEquals("123456789", service().preview("target").getAccount());
    }

    private String fingerprintFor(boolean reverseOrder, int memberVersion) {
        UserAccount target = user(1, "target", "Target", 1);
        Ledger first = ledger(10, "first", 1, null);
        Ledger second = ledger(20, "second", 1, null);
        LedgerMember firstMember = member(101, 10, 1, 1, null);
        LedgerMember secondMember = member(201, 20, 1, memberVersion, null);
        List<Ledger> ledgers = reverseOrder ? List.of(second, first) : List.of(first, second);
        List<LedgerMember> members = reverseOrder
                ? List.of(secondMember, firstMember) : List.of(firstMember, secondMember);
        when(userMapper.selectOne(any())).thenReturn(target);
        when(memberMapper.selectList(any())).thenReturn(members).thenReturn(members);
        when(ledgerMapper.selectList(any())).thenReturn(ledgers).thenReturn(ledgers);
        when(personMapper.selectCount(any())).thenReturn(0L, 0L);
        when(transactionMapper.selectCount(any())).thenReturn(0L, 0L);
        return service().preview("target").getFingerprint();
    }

    private AdminAccountDeletionService service() {
        return new AdminAccountDeletionService(adminService, userMapper, ledgerMapper,
                memberMapper, personMapper, transactionMapper);
    }

    private UserAccount user(long id, String uuid, String nickname, int status) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUuid(uuid);
        user.setNickname(nickname);
        user.setStatus(status);
        return user;
    }

    private Ledger ledger(long id, String uuid, long ownerId, LocalDateTime deletedAt) {
        Ledger ledger = new Ledger();
        ledger.setId(id);
        ledger.setUuid(uuid);
        ledger.setName(uuid + " name");
        ledger.setOwnerUserId(ownerId);
        ledger.setDeletedAt(deletedAt);
        return ledger;
    }

    private LedgerMember member(long id, long ledgerId, long userId, int version, LocalDateTime deletedAt) {
        LedgerMember member = new LedgerMember();
        member.setId(id);
        member.setUuid("member-" + id);
        member.setLedgerId(ledgerId);
        member.setUserId(userId);
        member.setRole(userId == 1 || ledgerId == 30 && userId == 4 ? "owner" : "editor");
        member.setStatus(1);
        member.setVersion(version);
        member.setDeletedAt(deletedAt);
        return member;
    }
}

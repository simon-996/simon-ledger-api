package com.simon.ledger.service.impl;

import cn.hutool.core.util.IdUtil;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.AdminAccountDeletionReq;
import com.simon.ledger.dto.resp.AdminAccountDeletionPreviewResp;
import com.simon.ledger.dto.resp.AdminUserResp;
import com.simon.ledger.service.AdminService;
import com.simon.ledger.service.ChangeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminAccountDeletionExecutor {

    private final AdminService adminService;
    private final AdminAccountDeletionService previewService;
    private final JdbcTemplate jdbc;
    private final ChangeLogService changeLogService;
    private final AccountDeletionSessions sessions;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String userUuid, AdminAccountDeletionReq req) {
        AdminUserResp admin = adminService.me();
        if (!Objects.equals(userUuid, req.getConfirmUuid())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "确认 UUID 与目标账号不一致");
        }
        Long userId = oneLong("SELECT id FROM user_account WHERE uuid = ? FOR UPDATE", userUuid);
        if (userId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<Long> ledgerIds = jdbc.queryForList("""
                SELECT id FROM ledger WHERE owner_user_id = ?
                UNION SELECT ledger_id FROM ledger_member WHERE user_id = ?
                UNION SELECT ledger_id FROM ledger_person WHERE linked_user_id = ?
                ORDER BY id
                """, Long.class, userId, userId, userId);
        for (Long ledgerId : ledgerIds) {
            if (oneLong("SELECT id FROM ledger WHERE id = ? FOR UPDATE", ledgerId) == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "账本状态已变化，请重新预览");
            }
        }

        AdminAccountDeletionPreviewResp preview = previewService.preview(userUuid);
        if (!Objects.equals(preview.getFingerprint(), req.getFingerprint())) {
            throw new BusinessException(ErrorCode.CONFLICT, "删除预览已过期，请重新获取");
        }
        Map<String, String> successors = validateSuccessors(preview, req.getSuccessors());
        invalidateOtherCachedResponses(userId, userUuid, preview);

        for (AdminAccountDeletionPreviewResp.LedgerImpact ledger : preview.getOwnedLedgers()) {
            Long ledgerId = oneLong("SELECT id FROM ledger WHERE uuid = ?", ledger.getUuid());
            if (ledgerId == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "账本状态已变化，请重新预览");
            }
            if ("DELETE".equals(ledger.getAction())) {
                deleteLedger(ledgerId);
            } else {
                Long successorId = oneLong("""
                        SELECT u.id FROM ledger_member m JOIN user_account u ON u.id = m.user_id
                        WHERE m.ledger_id = ? AND u.uuid = ? AND m.deleted_at IS NULL
                          AND m.status = 1 AND u.deleted_at IS NULL AND u.status = 1
                        """, ledgerId, successors.get(ledger.getUuid()));
                if (successorId == null || successorId.equals(userId)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "接手人状态已变化，请重新预览");
                }
                if (jdbc.update("UPDATE ledger SET owner_user_id = ?, version = version + 1 WHERE id = ? AND owner_user_id = ?",
                        successorId, ledgerId, userId) != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "账本所有权已变化，请重新预览");
                }
                jdbc.update("UPDATE ledger_member SET role = 'owner', version = version + 1 WHERE ledger_id = ? AND user_id = ?",
                        ledgerId, successorId);
                detachRetainedLedger(ledgerId, userId, ledger.getUuid(), true);
            }
        }
        for (AdminAccountDeletionPreviewResp.LedgerImpact ledger : preview.getJoinedLedgers()) {
            Long ledgerId = oneLong("SELECT id FROM ledger WHERE uuid = ?", ledger.getUuid());
            if (ledgerId != null) {
                detachRetainedLedger(ledgerId, userId, ledger.getUuid(), false);
            }
        }

        // Historical actors can reference the account even outside its current memberships.
        jdbc.update("UPDATE ledger_transaction SET created_by_user_id = NULL WHERE created_by_user_id = ?", userId);
        jdbc.update("UPDATE ledger_transaction SET last_modified_by_user_id = NULL WHERE last_modified_by_user_id = ?", userId);
        jdbc.update("UPDATE ledger_change_log SET operator_user_id = NULL WHERE operator_user_id = ?", userId);
        jdbc.update("DELETE FROM ledger_invite WHERE created_by_user_id = ?", userId);
        jdbc.update("DELETE FROM idempotency_record WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ledger_member WHERE user_id = ?", userId);
        jdbc.update("UPDATE ledger_person SET linked_user_id = NULL, avatar = '', version = version + 1 WHERE linked_user_id = ?", userId);
        jdbc.update("INSERT INTO account_session_revocation_queue (login_id) VALUES (?)", userId);
        if (jdbc.update("DELETE FROM user_account WHERE id = ?", userId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "账号状态已变化，请重新预览");
        }

        Long adminId = oneLong("SELECT id FROM admin_user WHERE uuid = ? AND deleted_at IS NULL", admin.getUuid());
        if (adminId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "后台账号不存在");
        }
        String detail = "转交账本=" + preview.getSummary().getLedgersToTransfer()
                + ",删除账本=" + preview.getSummary().getLedgersToDelete()
                + ",保留流水=" + preview.getSummary().getTransactionsToKeep()
                + ",删除流水=" + preview.getSummary().getTransactionsToDelete();
        jdbc.update("""
                INSERT INTO admin_operation_log (uuid, admin_user_id, action, target_type, target_uuid, detail)
                VALUES (?, ?, 'delete_account', 'user_account', NULL, ?)
                """, IdUtil.fastSimpleUUID(), adminId, detail);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.revoke(userId);
            }
        });
    }

    private Map<String, String> validateSuccessors(AdminAccountDeletionPreviewResp preview,
            List<AdminAccountDeletionReq.Successor> selections) {
        Map<String, String> selected = new HashMap<>();
        for (AdminAccountDeletionReq.Successor selection : selections) {
            if (selected.putIfAbsent(selection.getLedgerUuid(), selection.getUserUuid()) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "同一本账本重复指定接手人");
            }
        }
        Set<String> required = new HashSet<>();
        for (AdminAccountDeletionPreviewResp.LedgerImpact ledger : preview.getOwnedLedgers()) {
            if (!"TRANSFER".equals(ledger.getAction())) {
                continue;
            }
            required.add(ledger.getUuid());
            String successorUuid = selected.get(ledger.getUuid());
            if (ledger.getSuccessors().stream().noneMatch(member -> member.getUserUuid().equals(successorUuid))) {
                throw new BusinessException(ErrorCode.CONFLICT, "接手人不可用，请重新预览");
            }
        }
        if (!selected.keySet().equals(required)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请逐本指定需要转交的账本接手人");
        }
        return selected;
    }

    private void invalidateOtherCachedResponses(Long userId, String userUuid,
            AdminAccountDeletionPreviewResp preview) {
        // Keep idempotency keys reserved: deleting a key could re-execute a prior write.
        jdbc.update("""
                UPDATE idempotency_record SET response_code = -2, response_body = NULL
                WHERE user_id <> ? AND response_code <> -1
                  AND CAST(response_body AS CHAR) LIKE ?
                """, userId, "%" + userUuid + "%");
        List<AdminAccountDeletionPreviewResp.LedgerImpact> ledgers = new java.util.ArrayList<>(preview.getOwnedLedgers());
        ledgers.addAll(preview.getJoinedLedgers());
        for (AdminAccountDeletionPreviewResp.LedgerImpact ledger : ledgers) {
            invalidatePath(userId, "%/ledgers/" + ledger.getUuid() + "%");
            Long ledgerId = oneLong("SELECT id FROM ledger WHERE uuid = ?", ledger.getUuid());
            if (ledgerId != null) {
                for (String code : jdbc.queryForList("SELECT code FROM ledger_invite WHERE ledger_id = ?",
                        String.class, ledgerId)) {
                    invalidatePath(userId, "%/invites/" + code + "%");
                }
            }
        }
    }

    private void invalidatePath(Long userId, String pattern) {
        jdbc.update("""
                UPDATE idempotency_record SET response_code = -2, response_body = NULL
                WHERE user_id <> ? AND response_code <> -1 AND request_path LIKE ?
                """, userId, pattern);
    }

    private void detachRetainedLedger(Long ledgerId, Long userId, String ledgerUuid, boolean transferred) {
        List<String> people = jdbc.queryForList("""
                SELECT uuid FROM ledger_person WHERE ledger_id = ? AND linked_user_id = ? AND deleted_at IS NULL
                """, String.class, ledgerId, userId);
        List<String> transactions = jdbc.queryForList("""
                SELECT uuid FROM ledger_transaction WHERE ledger_id = ? AND deleted_at IS NULL
                  AND (created_by_user_id = ? OR last_modified_by_user_id = ?)
                """, String.class, ledgerId, userId, userId);
        List<String> memberships = jdbc.queryForList("""
                SELECT uuid FROM ledger_member WHERE ledger_id = ? AND user_id = ? AND deleted_at IS NULL
                """, String.class, ledgerId, userId);
        jdbc.update("""
                UPDATE ledger_person SET linked_user_id = NULL, avatar = '', version = version + 1
                WHERE ledger_id = ? AND linked_user_id = ?
                """, ledgerId, userId);
        jdbc.update("UPDATE ledger_transaction SET created_by_user_id = NULL WHERE ledger_id = ? AND created_by_user_id = ?",
                ledgerId, userId);
        jdbc.update("""
                UPDATE ledger_transaction SET last_modified_by_user_id = NULL
                WHERE ledger_id = ? AND last_modified_by_user_id = ?
                """, ledgerId, userId);
        jdbc.update("DELETE FROM ledger_invite WHERE ledger_id = ? AND created_by_user_id = ?", ledgerId, userId);
        jdbc.update("DELETE FROM ledger_member WHERE ledger_id = ? AND user_id = ?", ledgerId, userId);

        if (transferred) {
            changeLogService.record(ledgerId, "ledger", ledgerUuid, "update", null);
        }
        for (String uuid : people) {
            changeLogService.record(ledgerId, "person", uuid, "update", null);
        }
        for (String uuid : transactions) {
            changeLogService.record(ledgerId, "transaction", uuid, "update", null);
        }
        for (String uuid : memberships) {
            changeLogService.record(ledgerId, "member", uuid, "delete", null);
        }
    }

    private void deleteLedger(Long ledgerId) {
        jdbc.update("""
                DELETE tp FROM ledger_transaction_person tp
                JOIN ledger_transaction t ON t.id = tp.transaction_id WHERE t.ledger_id = ?
                """, ledgerId);
        jdbc.update("DELETE FROM ledger_invite WHERE ledger_id = ?", ledgerId);
        jdbc.update("DELETE FROM ledger_change_log WHERE ledger_id = ?", ledgerId);
        jdbc.update("DELETE FROM ledger_transaction WHERE ledger_id = ?", ledgerId);
        jdbc.update("DELETE FROM ledger_person WHERE ledger_id = ?", ledgerId);
        jdbc.update("DELETE FROM ledger_member WHERE ledger_id = ?", ledgerId);
        jdbc.update("DELETE FROM ledger WHERE id = ?", ledgerId);
    }

    private Long oneLong(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, Long.class, args);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }
}

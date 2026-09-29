package com.simon.ledger.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminAccountDeletionService {

    private final AdminService adminService;
    private final UserAccountMapper userAccountMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final LedgerPersonMapper ledgerPersonMapper;
    private final LedgerTransactionMapper ledgerTransactionMapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminAccountDeletionPreviewResp preview(String userUuid) {
        adminService.me();
        UserAccount target = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUuid, userUuid));
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<LedgerMember> targetMemberships = ledgerMemberMapper.selectList(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getUserId, target.getId()));
        List<LedgerPerson> linkedPeople = ledgerPersonMapper.selectList(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLinkedUserId, target.getId()));
        List<Ledger> owned = ledgerMapper.selectList(Wrappers.<Ledger>lambdaQuery()
                .eq(Ledger::getOwnerUserId, target.getId()));
        Set<Long> memberLedgerIds = targetMemberships.stream().map(LedgerMember::getLedgerId).collect(Collectors.toSet());
        linkedPeople.stream().map(LedgerPerson::getLedgerId).forEach(memberLedgerIds::add);
        Map<Long, Ledger> affected = new HashMap<>();
        owned.forEach(ledger -> affected.put(ledger.getId(), ledger));
        if (!memberLedgerIds.isEmpty()) {
            ledgerMapper.selectList(Wrappers.<Ledger>lambdaQuery().in(Ledger::getId, memberLedgerIds))
                    .forEach(ledger -> affected.put(ledger.getId(), ledger));
        }
        List<Ledger> ledgers = affected.values().stream().sorted(Comparator.comparing(Ledger::getId)).toList();
        List<LedgerMember> members = ledgers.isEmpty() ? List.of()
                : ledgerMemberMapper.selectList(Wrappers.<LedgerMember>lambdaQuery()
                        .in(LedgerMember::getLedgerId, ledgers.stream().map(Ledger::getId).toList()));
        Map<Long, List<LedgerMember>> membersByLedger = members.stream()
                .collect(Collectors.groupingBy(LedgerMember::getLedgerId));
        Set<Long> otherUserIds = members.stream().map(LedgerMember::getUserId)
                .filter(id -> !id.equals(target.getId())).collect(Collectors.toSet());
        Map<Long, UserAccount> users = otherUserIds.isEmpty() ? Map.of()
                : userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, otherUserIds))
                        .stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        AdminAccountDeletionPreviewResp response = new AdminAccountDeletionPreviewResp();
        response.setUserUuid(target.getUuid());
        response.setNickname(target.getNickname());
        response.setAccount(StringUtils.hasText(target.getEmail()) ? target.getEmail() : target.getPhone());

        // DELETE must recompute this snapshot after locking the affected rows and compare fingerprints.
        StringBuilder snapshot = new StringBuilder("admin-account-deletion-preview-v1\n");
        append(snapshot, "user", target.getId(), target.getUuid(), target.getVersion(),
                target.getStatus(), target.getDeletedAt());
        for (Ledger ledger : ledgers) {
            List<LedgerMember> ledgerMembers = new ArrayList<>(membersByLedger.getOrDefault(ledger.getId(), List.of()));
            ledgerMembers.sort(Comparator.comparing(LedgerMember::getId));
            AdminAccountDeletionPreviewResp.LedgerImpact impact = impact(target, ledger, ledgerMembers, users);
            if (Objects.equals(ledger.getOwnerUserId(), target.getId())) {
                response.getOwnedLedgers().add(impact);
            } else {
                response.getJoinedLedgers().add(impact);
            }
            updateSummary(response.getSummary(), impact);
            append(snapshot, "ledger", ledger.getId(), ledger.getUuid(), ledger.getVersion(),
                    ledger.getOwnerUserId(), ledger.getDeletedAt(), impact.getPersonCount(), impact.getTransactionCount());
            for (LedgerMember member : ledgerMembers) {
                append(snapshot, "member", member.getId(), member.getUuid(), member.getVersion(),
                        member.getUserId(), member.getRole(), member.getStatus(), member.getDeletedAt());
            }
        }
        users.values().stream().sorted(Comparator.comparing(UserAccount::getId))
                .forEach(candidate -> append(snapshot, "member-user", candidate.getId(), candidate.getVersion(),
                        candidate.getStatus(), candidate.getDeletedAt()));
        linkedPeople.stream().sorted(Comparator.comparing(LedgerPerson::getId))
                .forEach(person -> append(snapshot, "linked-person", person.getId(), person.getUuid(),
                        person.getLedgerId(), person.getVersion(), person.getLinkedUserId(), person.getDeletedAt()));
        response.setFingerprint(sha256(snapshot.toString()));
        return response;
    }

    private AdminAccountDeletionPreviewResp.LedgerImpact impact(UserAccount target, Ledger ledger,
            List<LedgerMember> members, Map<Long, UserAccount> users) {
        AdminAccountDeletionPreviewResp.LedgerImpact impact = new AdminAccountDeletionPreviewResp.LedgerImpact();
        impact.setUuid(ledger.getUuid());
        impact.setName(ledger.getName());
        impact.setDeleted(ledger.getDeletedAt() != null);
        impact.setMemberCount(members.size());
        int people = Math.toIntExact(ledgerPersonMapper.selectCount(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledger.getId())));
        int transactions = Math.toIntExact(ledgerTransactionMapper.selectCount(Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledger.getId())));
        impact.setPersonCount(people);
        impact.setTransactionCount(transactions);

        for (LedgerMember member : members) {
            UserAccount user = Objects.equals(member.getUserId(), target.getId())
                    ? target : users.get(member.getUserId());
            if (ledger.getDeletedAt() != null || member.getDeletedAt() != null
                    || !Objects.equals(member.getStatus(), 1) || user == null
                    || user.getDeletedAt() != null || !Objects.equals(user.getStatus(), 1)) {
                continue;
            }
            AdminAccountDeletionPreviewResp.Member active = new AdminAccountDeletionPreviewResp.Member();
            active.setUserUuid(user.getUuid());
            active.setNickname(user.getNickname());
            active.setRole(member.getRole());
            impact.getActiveMembers().add(active);
            if (!Objects.equals(member.getUserId(), target.getId())
                    && Objects.equals(ledger.getOwnerUserId(), target.getId())) {
                impact.getSuccessors().add(active);
            }
        }

        if (Objects.equals(ledger.getOwnerUserId(), target.getId())
                && (ledger.getDeletedAt() != null || impact.getSuccessors().isEmpty())) {
            impact.setAction("DELETE");
            impact.setDeletedMemberCount(members.size());
            impact.setDeletedPersonCount(people);
            impact.setDeletedTransactionCount(transactions);
        } else {
            impact.setAction(Objects.equals(ledger.getOwnerUserId(), target.getId()) ? "TRANSFER" : "DETACH");
            int removedMembers = (int) members.stream()
                    .filter(member -> Objects.equals(member.getUserId(), target.getId())).count();
            impact.setDeletedMemberCount(removedMembers);
            impact.setRetainedMemberCount(members.size() - removedMembers);
            impact.setRetainedPersonCount(people);
            impact.setRetainedTransactionCount(transactions);
        }
        return impact;
    }

    private void updateSummary(AdminAccountDeletionPreviewResp.Summary summary,
            AdminAccountDeletionPreviewResp.LedgerImpact impact) {
        switch (impact.getAction()) {
            case "TRANSFER" -> summary.setLedgersToTransfer(summary.getLedgersToTransfer() + 1);
            case "DELETE" -> summary.setLedgersToDelete(summary.getLedgersToDelete() + 1);
            case "DETACH" -> summary.setOtherLedgers(summary.getOtherLedgers() + 1);
            default -> throw new IllegalStateException("Unknown deletion preview action");
        }
        summary.setPeopleToKeep(summary.getPeopleToKeep() + impact.getRetainedPersonCount());
        summary.setPeopleToDelete(summary.getPeopleToDelete() + impact.getDeletedPersonCount());
        summary.setTransactionsToKeep(summary.getTransactionsToKeep() + impact.getRetainedTransactionCount());
        summary.setTransactionsToDelete(summary.getTransactionsToDelete() + impact.getDeletedTransactionCount());
    }

    private void append(StringBuilder snapshot, Object... parts) {
        for (Object part : parts) {
            String value = part == null ? "" : part.toString();
            snapshot.append(value.length()).append(':').append(value);
        }
        snapshot.append('\n');
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

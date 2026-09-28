package com.simon.ledger.dto.resp;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdminAccountDeletionPreviewResp {

    private String userUuid;
    private String nickname;
    private String account;
    private String fingerprint;
    private List<LedgerImpact> ownedLedgers = new ArrayList<>();
    private List<LedgerImpact> joinedLedgers = new ArrayList<>();
    private Summary summary = new Summary();

    @Data
    public static class Member {
        private String userUuid;
        private String nickname;
        private String role;
    }

    @Data
    public static class LedgerImpact {
        private String uuid;
        private String name;
        private boolean deleted;
        private String action;
        private List<Member> activeMembers = new ArrayList<>();
        private List<Member> successors = new ArrayList<>();
        private int memberCount;
        private int retainedMemberCount;
        private int deletedMemberCount;
        private int personCount;
        private int transactionCount;
        private int retainedPersonCount;
        private int deletedPersonCount;
        private int retainedTransactionCount;
        private int deletedTransactionCount;
    }

    @Data
    public static class Summary {
        private int ledgersToTransfer;
        private int ledgersToDelete;
        private int otherLedgers;
        private int peopleToKeep;
        private int peopleToDelete;
        private int transactionsToKeep;
        private int transactionsToDelete;
    }
}

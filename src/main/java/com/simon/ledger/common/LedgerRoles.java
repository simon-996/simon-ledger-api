package com.simon.ledger.common;

import java.util.Set;

public final class LedgerRoles {

    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String VIEWER = "viewer";

    private static final Set<String> MANAGE_LEDGER_ROLES = Set.of(OWNER, ADMIN);

    private LedgerRoles() {
    }

    public static boolean canManageLedger(String role) {
        return MANAGE_LEDGER_ROLES.contains(role);
    }

    public static boolean isOwner(String role) {
        return OWNER.equals(role);
    }
}

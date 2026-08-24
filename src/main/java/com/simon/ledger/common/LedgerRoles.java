package com.simon.ledger.common;

import java.util.Set;

public final class LedgerRoles {

    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String VIEWER = "viewer";

    private static final Set<String> MANAGE_LEDGER_ROLES = Set.of(OWNER, ADMIN);
    private static final Set<String> EDIT_TRANSACTION_ROLES = Set.of(OWNER, ADMIN, EDITOR);
    private static final Set<String> JOINABLE_ROLES = Set.of(ADMIN, EDITOR, VIEWER);
    private static final Set<String> MEMBER_ROLES = Set.of(OWNER, ADMIN, EDITOR, VIEWER);

    private LedgerRoles() {
    }

    public static boolean canManageLedger(String role) {
        return MANAGE_LEDGER_ROLES.contains(role);
    }

    public static boolean isOwner(String role) {
        return OWNER.equals(role);
    }

    public static boolean canCreateTransaction(String role) {
        return EDIT_TRANSACTION_ROLES.contains(role);
    }

    public static boolean canEditAnyTransaction(String role) {
        return OWNER.equals(role) || ADMIN.equals(role);
    }

    public static boolean isValidJoinableRole(String role) {
        return role != null && JOINABLE_ROLES.contains(role);
    }

    public static boolean canAssignRole(String operatorRole, String targetRole, String newRole) {
        if (!isValidMemberRole(operatorRole) || !isValidMemberRole(targetRole)
                || !isValidJoinableRole(newRole) || OWNER.equals(targetRole)) {
            return false;
        }
        if (OWNER.equals(operatorRole)) {
            return true;
        }
        return ADMIN.equals(operatorRole)
                && (EDITOR.equals(targetRole) || VIEWER.equals(targetRole))
                && (EDITOR.equals(newRole) || VIEWER.equals(newRole));
    }

    public static boolean canRemoveRole(String operatorRole, String targetRole) {
        if (!isValidMemberRole(operatorRole) || !isValidMemberRole(targetRole) || OWNER.equals(targetRole)) {
            return false;
        }
        if (OWNER.equals(operatorRole)) {
            return true;
        }
        return ADMIN.equals(operatorRole) && (EDITOR.equals(targetRole) || VIEWER.equals(targetRole));
    }

    public static boolean canCreateInvite(String operatorRole, String invitedRole) {
        if (!isValidMemberRole(operatorRole) || !isValidJoinableRole(invitedRole)) {
            return false;
        }
        return OWNER.equals(operatorRole)
                || ADMIN.equals(operatorRole) && (EDITOR.equals(invitedRole) || VIEWER.equals(invitedRole));
    }

    private static boolean isValidMemberRole(String role) {
        return role != null && MEMBER_ROLES.contains(role);
    }
}

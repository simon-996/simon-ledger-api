package com.simon.ledger.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerRolesTests {

    @Test
    void ownerAndAdminCanManageLedger() {
        assertTrue(LedgerRoles.canManageLedger(LedgerRoles.OWNER));
        assertTrue(LedgerRoles.canManageLedger(LedgerRoles.ADMIN));
        assertFalse(LedgerRoles.canManageLedger(LedgerRoles.EDITOR));
        assertFalse(LedgerRoles.canManageLedger(LedgerRoles.VIEWER));
    }

    @Test
    void viewerCannotCreateOrEditTransactions() {
        assertTrue(LedgerRoles.canCreateTransaction(LedgerRoles.EDITOR));
        assertFalse(LedgerRoles.canCreateTransaction(LedgerRoles.VIEWER));
        assertTrue(LedgerRoles.canEditAnyTransaction(LedgerRoles.OWNER));
        assertFalse(LedgerRoles.canEditAnyTransaction(LedgerRoles.EDITOR));
    }

    @Test
    void onlyNonOwnerRolesCanBeInviteDefaults() {
        assertTrue(LedgerRoles.isValidJoinableRole(LedgerRoles.ADMIN));
        assertTrue(LedgerRoles.isValidJoinableRole(LedgerRoles.EDITOR));
        assertTrue(LedgerRoles.isValidJoinableRole(LedgerRoles.VIEWER));
        assertFalse(LedgerRoles.isValidJoinableRole(LedgerRoles.OWNER));
    }
}

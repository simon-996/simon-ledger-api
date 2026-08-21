package com.simon.ledger.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @ParameterizedTest(name = "{0} assigning {2} to {1} is {3}")
    @MethodSource("roleAssignmentMatrix")
    void roleAssignmentUsesExplicitAuthorityMatrix(String operatorRole, String targetRole,
                                                    String newRole, boolean allowed) {
        assertEquals(allowed, LedgerRoles.canAssignRole(operatorRole, targetRole, newRole));
    }

    @ParameterizedTest(name = "{0} removing {1} is {2}")
    @MethodSource("roleRemovalMatrix")
    void roleRemovalUsesExplicitAuthorityMatrix(String operatorRole, String targetRole, boolean allowed) {
        assertEquals(allowed, LedgerRoles.canRemoveRole(operatorRole, targetRole));
    }

    private static Stream<Arguments> roleAssignmentMatrix() {
        return Stream.of(
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.EDITOR, LedgerRoles.ADMIN, true),
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.ADMIN, LedgerRoles.EDITOR, true),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.EDITOR, LedgerRoles.VIEWER, true),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.EDITOR, LedgerRoles.ADMIN, false),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.ADMIN, LedgerRoles.EDITOR, false),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.VIEWER, LedgerRoles.EDITOR, true),
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.OWNER, LedgerRoles.VIEWER, false),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.OWNER, LedgerRoles.VIEWER, false),
                Arguments.of(LedgerRoles.EDITOR, LedgerRoles.VIEWER, LedgerRoles.EDITOR, false),
                Arguments.of(LedgerRoles.VIEWER, LedgerRoles.EDITOR, LedgerRoles.VIEWER, false),
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.EDITOR, LedgerRoles.OWNER, false),
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.EDITOR, "invalid", false)
        );
    }

    private static Stream<Arguments> roleRemovalMatrix() {
        return Stream.of(
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.ADMIN, true),
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.EDITOR, true),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.EDITOR, true),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.VIEWER, true),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.ADMIN, false),
                Arguments.of(LedgerRoles.OWNER, LedgerRoles.OWNER, false),
                Arguments.of(LedgerRoles.ADMIN, LedgerRoles.OWNER, false),
                Arguments.of(LedgerRoles.EDITOR, LedgerRoles.VIEWER, false),
                Arguments.of(LedgerRoles.VIEWER, LedgerRoles.EDITOR, false)
        );
    }
}

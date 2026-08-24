package com.simon.ledger.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerRolesTests {

    private static final List<String> ROLE_INPUTS = Arrays.asList(
            LedgerRoles.OWNER, LedgerRoles.ADMIN, LedgerRoles.EDITOR, LedgerRoles.VIEWER, "invalid", null);

    private static final Set<Assignment> ALLOWED_ASSIGNMENTS = Set.of(
            new Assignment(LedgerRoles.OWNER, LedgerRoles.ADMIN, LedgerRoles.ADMIN),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.ADMIN, LedgerRoles.EDITOR),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.ADMIN, LedgerRoles.VIEWER),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.EDITOR, LedgerRoles.ADMIN),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.EDITOR, LedgerRoles.EDITOR),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.EDITOR, LedgerRoles.VIEWER),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.VIEWER, LedgerRoles.ADMIN),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.VIEWER, LedgerRoles.EDITOR),
            new Assignment(LedgerRoles.OWNER, LedgerRoles.VIEWER, LedgerRoles.VIEWER),
            new Assignment(LedgerRoles.ADMIN, LedgerRoles.EDITOR, LedgerRoles.EDITOR),
            new Assignment(LedgerRoles.ADMIN, LedgerRoles.EDITOR, LedgerRoles.VIEWER),
            new Assignment(LedgerRoles.ADMIN, LedgerRoles.VIEWER, LedgerRoles.EDITOR),
            new Assignment(LedgerRoles.ADMIN, LedgerRoles.VIEWER, LedgerRoles.VIEWER)
    );

    private static final Set<Removal> ALLOWED_REMOVALS = Set.of(
            new Removal(LedgerRoles.OWNER, LedgerRoles.ADMIN),
            new Removal(LedgerRoles.OWNER, LedgerRoles.EDITOR),
            new Removal(LedgerRoles.OWNER, LedgerRoles.VIEWER),
            new Removal(LedgerRoles.ADMIN, LedgerRoles.EDITOR),
            new Removal(LedgerRoles.ADMIN, LedgerRoles.VIEWER)
    );

    private static final Set<InviteCreation> ALLOWED_INVITES = Set.of(
            new InviteCreation(LedgerRoles.OWNER, LedgerRoles.ADMIN),
            new InviteCreation(LedgerRoles.OWNER, LedgerRoles.EDITOR),
            new InviteCreation(LedgerRoles.OWNER, LedgerRoles.VIEWER),
            new InviteCreation(LedgerRoles.ADMIN, LedgerRoles.EDITOR),
            new InviteCreation(LedgerRoles.ADMIN, LedgerRoles.VIEWER)
    );

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

    @ParameterizedTest(name = "{0} inviting {1} is {2}")
    @MethodSource("inviteCreationMatrix")
    void inviteCreationUsesExplicitAuthorityMatrix(String operatorRole, String invitedRole, boolean allowed) {
        assertEquals(allowed, LedgerRoles.canCreateInvite(operatorRole, invitedRole));
    }

    private static Stream<Arguments> roleAssignmentMatrix() {
        return ROLE_INPUTS.stream().flatMap(operator -> ROLE_INPUTS.stream().flatMap(target ->
                ROLE_INPUTS.stream().map(next -> Arguments.of(
                        operator, target, next, ALLOWED_ASSIGNMENTS.contains(new Assignment(operator, target, next))))));
    }

    private static Stream<Arguments> roleRemovalMatrix() {
        return ROLE_INPUTS.stream().flatMap(operator -> ROLE_INPUTS.stream().map(target -> Arguments.of(
                operator, target, ALLOWED_REMOVALS.contains(new Removal(operator, target)))));
    }

    private static Stream<Arguments> inviteCreationMatrix() {
        return ROLE_INPUTS.stream().flatMap(operator -> ROLE_INPUTS.stream().map(invited -> Arguments.of(
                operator, invited, ALLOWED_INVITES.contains(new InviteCreation(operator, invited)))));
    }

    private record Assignment(String operatorRole, String targetRole, String newRole) { }

    private record Removal(String operatorRole, String targetRole) { }

    private record InviteCreation(String operatorRole, String invitedRole) { }
}

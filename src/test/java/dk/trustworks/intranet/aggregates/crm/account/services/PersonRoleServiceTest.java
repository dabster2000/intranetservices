package dk.trustworks.intranet.aggregates.crm.account.services;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared per-person role check every CRM management rule goes through — the fix for
 * the pattern the gap analysis called D1, which had already appeared twice.
 */
class PersonRoleServiceTest {

    @Test
    void managementIsAdminOrPartner() {
        assertTrue(PersonRoleService.holdsAny(Set.of("USER", "PARTNER"), PersonRoleService.MANAGEMENT_ROLES));
        assertTrue(PersonRoleService.holdsAny(Set.of("ADMIN"), PersonRoleService.MANAGEMENT_ROLES));
        assertFalse(PersonRoleService.holdsAny(Set.of("USER", "SALES", "TEAMLEAD"), PersonRoleService.MANAGEMENT_ROLES));
    }

    /** The scopes the BFF's token carries are not roles; {@code admin:*} must never read as ADMIN. */
    @Test
    void aScopeIsNotARole() {
        assertFalse(PersonRoleService.holdsAny(Set.of("admin:*", "accounts:write"), PersonRoleService.MANAGEMENT_ROLES));
        assertFalse(PersonRoleService.holdsAny(Set.of("admin:*"), PersonRoleService.COST_ROLES));
    }

    @Test
    void costFiguresNeedOneOfTheFourCostRoles() {
        assertTrue(PersonRoleService.holdsAny(Set.of("CXO"), PersonRoleService.COST_ROLES));
        assertTrue(PersonRoleService.holdsAny(Set.of("TECHPARTNER"), PersonRoleService.COST_ROLES));
        assertFalse(PersonRoleService.holdsAny(Set.of("SALES", "USER"), PersonRoleService.COST_ROLES));
    }

    @Test
    void nothingHeldMeansNothingGranted() {
        assertFalse(PersonRoleService.holdsAny(Set.of(), PersonRoleService.MANAGEMENT_ROLES));
        assertFalse(PersonRoleService.holdsAny(null, PersonRoleService.MANAGEMENT_ROLES));
        assertFalse(PersonRoleService.holdsAny(Set.of("ADMIN"), null));
    }
}

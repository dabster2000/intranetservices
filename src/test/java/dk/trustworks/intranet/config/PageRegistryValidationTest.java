package dk.trustworks.intranet.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6, task 6.10 — page_registry write-path validation.
 *
 * Before this phase the roles write path stored {@code body.roles.join(',')} verbatim
 * (lowercase persisted, only rescued on read) and accepted values naming no real role,
 * silently making a page unreachable. Fast tier: gates every deploy.
 */
class PageRegistryValidationTest {

    private static final Set<String> KNOWN = Set.of("ADMIN", "HR", "USER", "TEAMLEAD");

    @Test
    void rolesAreUppercasedTrimmedAndDeduped() {
        var result = PageRegistryValidation.normalizeAndValidateRoles(" hr, admin ,HR ", KNOWN);
        assertTrue(result.valid());
        assertEquals("HR,ADMIN", result.normalized());
    }

    @Test
    void unknownRoleIsRejectedWithItsName() {
        var result = PageRegistryValidation.normalizeAndValidateRoles("ADMIN,MANAGER", KNOWN);
        assertFalse(result.valid());
        assertEquals(java.util.List.of("MANAGER"), result.unknown());
    }

    @Test
    void emptyRoleListIsRejected() {
        var result = PageRegistryValidation.normalizeAndValidateRoles(" , ,", KNOWN);
        assertFalse(result.valid());
    }

    @Test
    void permissionIsLowercasedAndValidatedAgainstCatalogue() {
        var result = PageRegistryValidation.normalizeAndValidatePermission(" Invoices:WRITE ");
        assertTrue(result.valid());
        assertEquals("invoices:write", result.normalized());
    }

    @Test
    void unknownPermissionIsRejected() {
        var result = PageRegistryValidation.normalizeAndValidatePermission("invoices:frobnicate");
        assertFalse(result.valid());
        assertEquals("invoices:frobnicate", result.normalized());
    }
}

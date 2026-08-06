package dk.trustworks.intranet.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protected permission set (Phase 7, task 7.9): admin:* and salaries:* role
 * bindings can only change in code, never through the console.
 */
class ProtectedPermissionsTest {

    @Test
    void adminFamilyIsProtected() {
        assertTrue(ProtectedPermissions.isProtected("admin:read"));
        assertTrue(ProtectedPermissions.isProtected("admin:write"));
        assertTrue(ProtectedPermissions.isProtected("admin:*"));
    }

    @Test
    void salariesFamilyIsProtected() {
        assertTrue(ProtectedPermissions.isProtected("salaries:read"));
        assertTrue(ProtectedPermissions.isProtected("salaries:write"));
    }

    @Test
    void caseAndWhitespaceDoNotBypassTheRail() {
        assertTrue(ProtectedPermissions.isProtected("ADMIN:READ"));
        assertTrue(ProtectedPermissions.isProtected("  Salaries:Write  "));
    }

    @Test
    void ordinaryPermissionsAreNotProtected() {
        assertFalse(ProtectedPermissions.isProtected("invoices:write"));
        assertFalse(ProtectedPermissions.isProtected("users:read"));
        assertFalse(ProtectedPermissions.isProtected(null));
        // Prefix must match the family exactly — "administration:x" is not "admin:x".
        assertFalse(ProtectedPermissions.isProtected("administration:read"));
    }

    @Test
    void everyProtectedKeyInTheCatalogueIsCoveredByThePrefixes() {
        // Pins the rail to the real catalogue: exactly the admin/salaries families.
        long protectedCount = Permissions.allKeys().stream()
                .filter(ProtectedPermissions::isProtected)
                .count();
        // admin:read, admin:write, admin:*, salaries:read, salaries:write
        org.junit.jupiter.api.Assertions.assertEquals(5, protectedCount);
    }
}

package dk.trustworks.intranet.security;

import java.util.List;
import java.util.Locale;

/**
 * The protected permission set (Phase 7, task 7.9).
 *
 * <p>Making the admin console authoritative means an admin could grant themselves
 * anything. The highest-value grants therefore stay code changes: the UI displays
 * {@code admin:*}-family and {@code salaries:*}-family bindings but every rebind —
 * grant or revoke — is refused at the service layer. Changing who holds these means
 * editing the seed migrations, which goes through review.
 *
 * <p>This protects the <em>role → permission</em> binding only. Gating a page BY
 * {@code salaries:read} (page_registry.required_permission) is restriction, not
 * escalation, and stays editable.
 */
public final class ProtectedPermissions {

    /** Prefixes of permission keys whose role bindings the UI must never rebind. */
    public static final List<String> PROTECTED_PREFIXES = List.of("admin:", "salaries:");

    private ProtectedPermissions() {
    }

    public static boolean isProtected(String permissionKey) {
        if (permissionKey == null) {
            return false;
        }
        String key = permissionKey.trim().toLowerCase(Locale.ROOT);
        return PROTECTED_PREFIXES.stream().anyMatch(key::startsWith);
    }
}

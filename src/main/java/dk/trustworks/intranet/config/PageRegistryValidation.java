package dk.trustworks.intranet.config;

import dk.trustworks.intranet.security.Permissions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Write-path validation for page_registry (Phase 6, task 6.10).
 *
 * Before this class, {@code setRequiredRoles} accepted any non-blank string and stored it
 * verbatim while the read path uppercased — a lowercase value persisted and was only rescued
 * on read, and a value naming no real role silently made a page unreachable. Permissions are
 * validated against the code catalogue ({@link Permissions}), which is identical in every
 * environment; roles are validated against the caller-supplied set of role_definition names,
 * because role_definition is UI-mutable and environment-specific. The V467 FK enforces the
 * permission constraint at the database level; this class exists to return a useful 400
 * instead of a 500.
 */
public final class PageRegistryValidation {

    private PageRegistryValidation() {
    }

    public record RolesResult(String normalized, List<String> unknown) {
        public boolean valid() {
            return unknown.isEmpty();
        }
    }

    public record PermissionResult(String normalized, boolean valid) {
    }

    /**
     * Uppercase, trim and dedupe a comma-separated role list, reporting entries that do not
     * exist in {@code knownRoleNames}. Returns the normalized CSV in input order.
     */
    public static RolesResult normalizeAndValidateRoles(String csv, Set<String> knownRoleNames) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String part : csv.split(",")) {
            String role = part.trim().toUpperCase(Locale.ROOT);
            if (role.isEmpty()) {
                continue;
            }
            if (!knownRoleNames.contains(role)) {
                unknown.add(role);
            }
            normalized.add(role);
        }
        if (normalized.isEmpty()) {
            unknown.add("(empty role list)");
        }
        return new RolesResult(String.join(",", normalized), List.copyOf(unknown));
    }

    /**
     * Lowercase and trim a permission key, validating it against the code catalogue.
     */
    public static PermissionResult normalizeAndValidatePermission(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return new PermissionResult(normalized, Permissions.allKeysAsSet().contains(normalized));
    }
}

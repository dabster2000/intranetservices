package dk.trustworks.intranet.security;

import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Write path for role → permission bindings (Phase 7, tasks 7.3 + 7.9).
 *
 * <p>Every mutation writes an {@code authz_audit} row and bumps {@code authz_version}
 * in the same transaction ({@link AuthzAuditService}); the bump is what makes the
 * change effective within ~1 s on every task (Phase 4.8 cache).
 *
 * <p>Safety rails enforced here, not in the UI (the UI mirrors them for usability):
 * <ul>
 *   <li><strong>Protected permission set</strong> — {@code admin:*} / {@code salaries:*}
 *       bindings cannot be granted or revoked through this path at all.</li>
 *   <li><strong>Tombstone revocation</strong> — revoke sets {@code revoked_at}, never
 *       deletes (F-13: a deleted row would be resurrected by a seed re-run).</li>
 * </ul>
 */
@ApplicationScoped
@JBossLog
public class RolePermissionAdminService {

    @Inject
    AuthzAuditService authzAuditService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** Attempted rebind of a protected permission ({@code admin:*} / {@code salaries:*}). */
    public static class ProtectedPermissionException extends RuntimeException {
        public ProtectedPermissionException(String permissionKey) {
            super("Permission '" + permissionKey + "' is protected (admin:*/salaries:*)."
                    + " Its role bindings can only be changed in code, through review —"
                    + " never from the admin console.");
        }
    }

    /** Permission key not present in the code catalogue. */
    public static class UnknownPermissionException extends RuntimeException {
        public UnknownPermissionException(String permissionKey) {
            super("Unknown permission: " + permissionKey);
        }
    }

    /**
     * Grants {@code permissionKey} to {@code role}. Idempotent: an already-active
     * binding is returned unchanged (no audit row, no version bump — nothing changed).
     * A tombstoned binding is re-activated.
     */
    @Transactional
    public RolePermission grant(String role, String permissionKey) {
        String key = normalizeAndValidateKey(permissionKey);
        String roleName = validateRole(role);

        RolePermission existing = RolePermission.findByRoleAndKey(roleName, key).orElse(null);
        if (existing != null && existing.isActive()) {
            return existing; // no-op
        }

        Map<String, Object> before = snapshot(existing);

        RolePermission binding;
        if (existing != null) {
            existing.setRevokedAt(null);
            existing.setGrantedBy(actor());
            existing.setGrantedAt(LocalDateTime.now());
            binding = existing;
        } else {
            binding = new RolePermission(roleName, key);
            binding.setGrantedBy(actor());
            binding.setGrantedAt(LocalDateTime.now());
            binding.persist();
        }

        authzAuditService.record(
                "ROLE_PERMISSION_GRANTED", "role_permission", roleName + ":" + key,
                before, snapshot(binding));
        return binding;
    }

    /**
     * Revokes {@code permissionKey} from {@code role} by tombstone. Idempotent: an
     * already-revoked binding is a no-op; a binding that never existed is a 404.
     */
    @Transactional
    public RolePermission revoke(String role, String permissionKey) {
        String key = normalizeAndValidateKey(permissionKey);
        String roleName = validateRole(role);

        RolePermission existing = RolePermission.findByRoleAndKey(roleName, key)
                .orElseThrow(() -> new NotFoundException(
                        "No binding exists for role '" + roleName + "' and permission '" + key + "'"));
        if (!existing.isActive()) {
            return existing; // no-op
        }

        Map<String, Object> before = snapshot(existing);
        existing.setRevokedAt(LocalDateTime.now());

        authzAuditService.record(
                "ROLE_PERMISSION_REVOKED", "role_permission", roleName + ":" + key,
                before, snapshot(existing));
        return existing;
    }

    private String normalizeAndValidateKey(String permissionKey) {
        if (permissionKey == null || permissionKey.isBlank()) {
            throw new UnknownPermissionException(String.valueOf(permissionKey));
        }
        String key = permissionKey.trim().toLowerCase(Locale.ROOT);
        if (ProtectedPermissions.isProtected(key)) {
            // Checked before catalogue membership so a probe cannot distinguish
            // "protected" from "unknown" ordering effects; the message is the rail.
            throw new ProtectedPermissionException(key);
        }
        if (!Permissions.allKeysAsSet().contains(key)) {
            throw new UnknownPermissionException(key);
        }
        return key;
    }

    private String validateRole(String role) {
        if (role == null || role.isBlank()) {
            throw new NotFoundException("Role is required");
        }
        String roleName = role.trim().toUpperCase(Locale.ROOT);
        RoleDefinition.findByName(roleName)
                .orElseThrow(() -> new NotFoundException("Role definition not found: " + roleName));
        return roleName;
    }

    private String actor() {
        try {
            String actor = requestHeaderHolder.getUserUuid();
            return (actor == null || actor.isEmpty()) ? "system" : actor;
        } catch (ContextNotActiveException e) {
            return "system";
        }
    }

    private Map<String, Object> snapshot(RolePermission binding) {
        if (binding == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("role", binding.getRole());
        snap.put("permissionKey", binding.getPermissionKey());
        snap.put("active", binding.isActive());
        snap.put("grantedBy", binding.getGrantedBy());
        snap.put("grantedAt", String.valueOf(binding.getGrantedAt()));
        snap.put("revokedAt", binding.getRevokedAt() == null ? null : String.valueOf(binding.getRevokedAt()));
        return snap;
    }
}

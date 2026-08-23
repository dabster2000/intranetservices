package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import dk.trustworks.intranet.security.AuthzAuditService;
import dk.trustworks.intranet.security.RequestHeaderHolder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class RoleService {

    @Inject
    AuthzAuditService authzAuditService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    EntityManager em;

    /**
     * Removing this assignment would strip the acting admin's own admin access, or
     * remove the last holder of a system role (Phase 7, task 7.9). Both are refused
     * so the console cannot lock the organisation out of itself.
     */
    public static class RoleAssignmentRailException extends RuntimeException {
        public RoleAssignmentRailException(String message) {
            super(message);
        }
    }

    public List<Role> listAll(@PathParam("useruuid") String useruuid) {
        return Role.findByUseruuid(useruuid);
    }

    // Role assignments are authorization writes: each writes an authz_audit row AND
    // bumps authz_version in the same transaction (Phase 7, task 7.2 — the audit
    // service does both), so every task's permission cache flushes within ~1 s.

    @Transactional
    public void create(String useruuid, @Valid Role role) {
        requirePracticeForAssistantTeamlead(useruuid, role);
        role.setUuid(UUID.randomUUID().toString());
        role.setUseruuid(useruuid);
        Role.persist(role);
        authzAuditService.record("USER_ROLE_ADDED", "user_role", useruuid,
                null, Map.of("useruuid", useruuid, "role", role.getRole()));
    }

    /**
     * The assignment rail for {@code ASSISTANT_TEAMLEAD} (recruitment access
     * model 2026-08-23, decision 3 + the empty-practice guard): the role's
     * entire reach is the practice the user belongs to
     * ({@code user.practice_uuid}), so handing it to a user with no practice
     * would produce a silently empty recruitment module for them — every
     * screen blank, no error anywhere. Refused here, at assignment time,
     * with a message that says what to fix instead.
     */
    private void requirePracticeForAssistantTeamlead(String useruuid, Role role) {
        if (role.getRole() == null
                || !"ASSISTANT_TEAMLEAD".equalsIgnoreCase(role.getRole().trim())) {
            return;
        }
        List<?> rows = em.createNativeQuery(
                        "SELECT practice_uuid FROM user WHERE uuid = :uuid")
                .setParameter("uuid", useruuid)
                .getResultList();
        boolean hasPractice = !rows.isEmpty()
                && rows.get(0) instanceof String practice && !practice.isBlank();
        if (!hasPractice) {
            throw new RoleAssignmentRailException(
                    "Refused: ASSISTANT_TEAMLEAD is scoped to the practice the user belongs to,"
                            + " and this user has no practice. Set their practice first"
                            + " (user.practice_uuid), then assign the role.");
        }
    }

    @Transactional
    public void delete(String useruuid) {
        List<Role> roles = Role.findByUseruuid(useruuid);
        for (Role role : roles) {
            guardRails(useruuid, role);
        }
        List<String> removed = roles.stream().map(Role::getRole).toList();
        Role.delete("useruuid", useruuid);
        authzAuditService.record("USER_ROLES_CLEARED", "user_role", useruuid,
                Map.of("useruuid", useruuid, "roles", removed), null);
    }

    @Transactional
    public void deleteByRoleUuid(String useruuid, String roleuuid) {
        Role role = Role.findById(roleuuid);
        if (role == null) {
            throw new NotFoundException("Role assignment not found: " + roleuuid);
        }
        guardRails(useruuid, role);
        Role.deleteById(roleuuid);
        authzAuditService.record("USER_ROLE_REMOVED", "user_role", role.getUseruuid(),
                Map.of("useruuid", role.getUseruuid(), "role", role.getRole()), null);
    }

    /**
     * The two removal rails (7.9):
     * <ol>
     *   <li><strong>Cannot remove your own admin.</strong> When the actor removes a role
     *       from themselves, and that role actively grants an {@code admin:*}-family
     *       permission, the removal is refused unless another of their roles still
     *       grants one.</li>
     *   <li><strong>Cannot remove the last holder of a system role.</strong></li>
     * </ol>
     */
    private void guardRails(String useruuid, Role role) {
        String actor = actor();
        String target = role.getUseruuid() != null ? role.getUseruuid() : useruuid;

        if (actor != null && actor.equals(target) && roleGrantsAdmin(role.getRole())
                && !holdsAdminViaOtherRole(target, role.getRole())) {
            throw new RoleAssignmentRailException(
                    "Refused: removing role '" + role.getRole() + "' from yourself would remove"
                            + " your own admin access. Have another admin remove it.");
        }

        boolean isSystemRole = RoleDefinition.findByName(role.getRole())
                .map(RoleDefinition::isSystem)
                .orElse(false);
        if (isSystemRole && Role.count("role", role.getRole()) <= 1) {
            throw new RoleAssignmentRailException(
                    "Refused: '" + role.getRole() + "' is a system role and this is its last"
                            + " holder. Assign it to someone else before removing it here.");
        }
    }

    private boolean roleGrantsAdmin(String roleName) {
        Number count = (Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM role_permission rp
                        JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                        WHERE rp.role = :role AND rp.revoked_at IS NULL
                          AND rp.permission_key LIKE 'admin:%'
                        """)
                .setParameter("role", roleName)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private boolean holdsAdminViaOtherRole(String useruuid, String excludedRole) {
        Number count = (Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM roles r
                        JOIN role_permission rp ON rp.role = r.role AND rp.revoked_at IS NULL
                        JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                        WHERE r.useruuid = :useruuid AND r.role <> :excludedRole
                          AND rp.permission_key LIKE 'admin:%'
                        """)
                .setParameter("useruuid", useruuid)
                .setParameter("excludedRole", excludedRole)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private String actor() {
        try {
            String actor = requestHeaderHolder.getUserUuid();
            return (actor == null || actor.isEmpty()) ? null : actor;
        } catch (ContextNotActiveException e) {
            return null;
        }
    }
}

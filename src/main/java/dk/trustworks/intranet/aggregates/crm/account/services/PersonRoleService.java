package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.domain.user.entity.Role;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The roles a PERSON holds, resolved from the {@code roles} table by user uuid.
 *
 * <p>This exists because {@code SecurityContext.isUserInRole("ADMIN")} is false for every
 * request that arrives through the frontend. The token the backend sees is the BFF's own
 * client credential, whose groups claim carries SCOPES ({@code accounts:write},
 * {@code admin:*}) and never the role names a person holds; the person is the
 * {@code X-Requested-By} header. Any "management may do this anywhere" rule written
 * against the security context is therefore dead code that does not look dead — the gap
 * analysis's D1 — and any rule written against {@code admin:*} is true for everyone,
 * which is the opposite mistake.
 *
 * <p>Every per-person rule in the CRM resolves through here: management overrides on
 * signals, who may set a sector lead, and who may see a salary-derived cost figure.
 */
@ApplicationScoped
public class PersonRoleService {

    /** ADMIN and PARTNER — "management" wherever the CRM says management may act anywhere. */
    public static final Set<String> MANAGEMENT_ROLES = Set.of("ADMIN", "PARTNER");

    /**
     * Who may see a salary-derived figure such as break-even. The same four roles the CXO
     * cost endpoints and the frontend's {@code CXO_SALARY_ROLES} use.
     */
    public static final Set<String> COST_ROLES = Set.of("ADMIN", "PARTNER", "CXO", "TECHPARTNER");

    /** The role names on the person's {@code roles} rows; empty for a blank or unknown uuid. */
    public Set<String> rolesOf(String userUuid) {
        Set<String> roles = new HashSet<>();
        if (userUuid == null || userUuid.isBlank()) {
            return roles;
        }
        for (Role role : Role.findByUseruuid(userUuid.trim())) {
            if (role.getRole() != null) {
                roles.add(role.getRole());
            }
        }
        return roles;
    }

    public boolean hasAnyRole(String userUuid, Collection<String> wanted) {
        return holdsAny(rolesOf(userUuid), wanted);
    }

    /** Pure, so the rule can be locked in the fast tier without a database. */
    public static boolean holdsAny(Set<String> held, Collection<String> wanted) {
        if (held == null || wanted == null) {
            return false;
        }
        for (String role : wanted) {
            if (held.contains(role)) {
                return true;
            }
        }
        return false;
    }

    public boolean isManagement(String userUuid) {
        return hasAnyRole(userUuid, MANAGEMENT_ROLES);
    }

    public boolean maySeeCost(String userUuid) {
        return hasAnyRole(userUuid, COST_ROLES);
    }
}

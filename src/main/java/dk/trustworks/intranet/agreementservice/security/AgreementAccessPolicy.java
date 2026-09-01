package dk.trustworks.intranet.agreementservice.security;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * End-user authorization for the agreement registry (template-clauses
 * spec §9, D9): registry data is salary-adjacent and visible to HR/ADMIN
 * only.
 *
 * <p>The service JWT only proves the BFF may call the backend. Human
 * authorization comes from {@code X-Requested-By}; a missing or
 * malformed value fails closed — same posture as
 * {@code TemplateAccessPolicy}, kept separate because the manager sets
 * may diverge (agreements are deliberately narrower than template
 * administration ever needs to be).</p>
 */
@ApplicationScoped
public class AgreementAccessPolicy {

    private static final Set<String> AGREEMENT_MANAGERS = Set.of("ADMIN", "HR");

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** Resolve and validate the acting human, never the system JWT principal. */
    public String requireActor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new ForbiddenException("Agreement access requires an identified user");
        }
        try {
            UUID.fromString(actor);
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("Agreement access requires a valid user");
        }
        return actor;
    }

    /** HR/ADMIN gate for every registry surface. Returns the actor UUID. */
    public String requireManager() {
        String actor = requireActor();
        boolean manager = Role.<Role>list("useruuid", actor).stream()
                .map(Role::getRole)
                .map(role -> role == null ? "" : role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet())
                .stream().anyMatch(AGREEMENT_MANAGERS::contains);
        if (!manager) {
            throw new ForbiddenException("The agreement registry is restricted to HR and ADMIN");
        }
        return actor;
    }
}

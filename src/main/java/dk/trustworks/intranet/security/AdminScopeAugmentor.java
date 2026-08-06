package dk.trustworks.intranet.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.Set;

/**
 * Augments identities that carry the {@code admin:*} scope by granting
 * every defined scope in the system.
 * <p>
 * This ensures that admin-level API clients (like the BFF) pass all
 * {@code @RolesAllowed} checks without needing to enumerate every scope
 * in their JWT claims. The augmentor runs once per request during
 * Quarkus security identity resolution.
 * <p>
 * The scope list must be kept in sync with the full scope catalogue
 * defined in the API client management system.
 */
@JBossLog
@ApplicationScoped
public class AdminScopeAugmentor implements SecurityIdentityAugmentor {

    static final String ADMIN_WILDCARD = Permissions.ADMIN_WILDCARD;

    /**
     * All defined scopes in the system. When a client holds {@code admin:*},
     * every scope below is added to its identity so that fine-grained
     * {@code @RolesAllowed} annotations pass without modification.
     *
     * <p>Since Phase 4 of the authorization-model unification the catalogue lives
     * in {@link Permissions}; this field is a view over it so the augmentor and the
     * permission catalogue cannot diverge. Runtime behaviour is unchanged.
     */
    static final Set<String> ALL_SCOPES = Permissions.allKeysAsSet();

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        if (!identity.getRoles().contains(ADMIN_WILDCARD)) {
            return Uni.createFrom().item(identity);
        }

        // Identity already has admin:* — expand to all scopes
        log.infof("AUDIT: Expanding admin:* identity to all %d scopes — subject=%s, name=%s",
                ALL_SCOPES.size(), identity.getPrincipal().getName(),
                identity.getAttribute("client_id") != null
                        ? identity.getAttribute("client_id")
                        : identity.getPrincipal().getName());

        QuarkusSecurityIdentity augmented = QuarkusSecurityIdentity.builder(identity)
                .addRoles(ALL_SCOPES)
                .build();

        return Uni.createFrom().item(augmented);
    }
}

package dk.trustworks.intranet.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Request-scoped helper for checking fine-grained scopes on the current
 * caller. Intended for use in resources or services that need conditional
 * data masking (e.g., hiding salary fields when the caller lacks
 * {@code salaries:read}).
 * <p>
 * All methods perform exact string matching against the identity's roles,
 * which include both the JWT-issued scopes and any scopes added by
 * {@link AdminScopeAugmentor}.
 */
@RequestScoped
public class ScopeContext {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    SecurityIdentity identity;

    /**
     * Returns {@code true} if the current identity holds the given scope.
     * Callers holding {@code admin:*} always pass (both via the augmentor
     * expanding all scopes and via the explicit check here as a safety net).
     *
     * @param scope the scope to check, e.g. {@code "salaries:read"}
     * @return true if the identity has the scope or admin wildcard
     * @throws NullPointerException if scope is null
     */
    public boolean hasScope(String scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        Set<String> roles = identity.getRoles();
        return roles.contains(ADMIN_WILDCARD) || roles.contains(scope);
    }

    /**
     * Returns {@code true} if the current identity holds <em>all</em> of the
     * given scopes.
     *
     * @param scopes one or more scopes to check
     * @return true if every scope is present
     * @throws NullPointerException     if scopes array is null
     * @throws IllegalArgumentException if scopes array is empty
     */
    public boolean hasAllScopes(String... scopes) {
        Objects.requireNonNull(scopes, "scopes must not be null");
        if (scopes.length == 0) {
            throw new IllegalArgumentException("At least one scope must be provided");
        }
        Set<String> roles = identity.getRoles();
        if (roles.contains(ADMIN_WILDCARD)) {
            return true;
        }
        return Arrays.stream(scopes).allMatch(roles::contains);
    }

    /**
     * Returns {@code true} if the current identity holds <em>any</em> of the
     * given scopes.
     *
     * @param scopes one or more scopes to check
     * @return true if at least one scope is present
     * @throws NullPointerException     if scopes array is null
     * @throws IllegalArgumentException if scopes array is empty
     */
    public boolean hasAnyScope(String... scopes) {
        Objects.requireNonNull(scopes, "scopes must not be null");
        if (scopes.length == 0) {
            throw new IllegalArgumentException("At least one scope must be provided");
        }
        Set<String> roles = identity.getRoles();
        if (roles.contains(ADMIN_WILDCARD)) {
            return true;
        }
        return Arrays.stream(scopes).anyMatch(roles::contains);
    }
}

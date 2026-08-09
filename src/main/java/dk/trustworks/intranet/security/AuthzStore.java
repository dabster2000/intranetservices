package dk.trustworks.intranet.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data access for the Phase 4 authorization catalogue tables
 * ({@code permission}, {@code role_permission}, {@code authz_version}).
 *
 * <p>An interface so {@link EffectivePermissionService} and
 * {@link PermissionCatalogueVerifier} can be unit-tested in the fast tier
 * without a database; {@link DbAuthzStore} is the runtime implementation.
 */
public interface AuthzStore {

    /**
     * Resolves a user's effective permission keys:
     * user → roles → role_permission → permission, tombstone-aware
     * ({@code revoked_at IS NULL} on both binding and permission).
     *
     * <p><strong>Since Phase 8 this is the legacy <em>boolean</em> projection:
     * only grants with {@code data_scope = 'ALL'} qualify.</strong> Every grant
     * that existed before V470 is ALL, so every pre-Phase-8 consumer (BFF
     * {@code can()}, page-registry audiences) sees an unchanged world; sub-ALL
     * grants are visible only to scope-aware consumers via
     * {@link #loadEffectivePermissionScopes} (owner decision 2026-08-06 —
     * without this rule, seeding {@code USER → salaries:read @ OWN} would have
     * shown the /salary-payment HR page to every employee).
     */
    Set<String> loadEffectivePermissions(String userUuid);

    /**
     * The scope-aware projection: every active grant reachable through the
     * user's roles, as permission key → the set of granted {@link DataScope}s
     * (one per granting role). Consumed by {@link AuthorizationServiceImpl};
     * never by the legacy boolean surface.
     */
    Map<String, Set<DataScope>> loadEffectivePermissionScopes(String userUuid);

    /** Current {@code authz_version} counter; 0 when the row is absent. */
    long currentVersion();

    /**
     * Bumps {@code authz_version} — MUST be called inside the transaction of the
     * authorization write it accompanies, so the version and the write commit or
     * roll back together. Every authorization write (role assignment changes
     * today; role_permission mutations from Phase 7) must call this.
     */
    void bumpVersion();

    /** One catalogue row, for the drift verifier. */
    record CatalogueRow(String permissionKey, String state, boolean revoked) {}

    /** Every row of the {@code permission} table, including tombstoned ones. */
    List<CatalogueRow> catalogueRows();

    /** Number of {@code role_permission} rows (including tombstoned) bound to a role. */
    long countPermissionBindings(String role);
}

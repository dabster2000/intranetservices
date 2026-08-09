package dk.trustworks.intranet.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime {@link AuthzStore} over the Phase 4 catalogue tables. Plain native
 * SQL with parameter binding — the tables deliberately have no Hibernate
 * entities while the catalogue is dormant (nothing consumes it until Phase 5),
 * which keeps boot-time mapping risk at zero.
 */
@ApplicationScoped
public class DbAuthzStore implements AuthzStore {

    @Inject
    EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> loadEffectivePermissions(String userUuid) {
        // data_scope = 'ALL' — the legacy boolean projection (Phase 8): sub-ALL
        // grants must never satisfy a scope-blind check. See AuthzStore javadoc.
        List<String> keys = em.createNativeQuery("""
                SELECT DISTINCT rp.permission_key
                FROM roles r
                JOIN role_permission rp ON rp.role = r.role AND rp.revoked_at IS NULL
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE r.useruuid = :useruuid AND rp.data_scope = 'ALL'
                """)
                .setParameter("useruuid", userUuid)
                .getResultList();
        return Set.copyOf(keys);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Set<DataScope>> loadEffectivePermissionScopes(String userUuid) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT rp.permission_key, rp.data_scope
                FROM roles r
                JOIN role_permission rp ON rp.role = r.role AND rp.revoked_at IS NULL
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE r.useruuid = :useruuid
                """)
                .setParameter("useruuid", userUuid)
                .getResultList();
        Map<String, Set<DataScope>> scopes = new HashMap<>();
        for (Object[] row : rows) {
            DataScope scope = DataScope.fromDb((String) row[1]);
            if (scope == null) {
                continue; // unparseable value — fail closed by dropping, never widening
            }
            scopes.computeIfAbsent((String) row[0], k -> EnumSet.noneOf(DataScope.class)).add(scope);
        }
        return scopes;
    }

    @Override
    public long currentVersion() {
        List<?> rows = em.createNativeQuery("SELECT version FROM authz_version WHERE id = 1").getResultList();
        if (rows.isEmpty()) return 0L;
        return ((Number) rows.get(0)).longValue();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void bumpVersion() {
        em.createNativeQuery("UPDATE authz_version SET version = version + 1 WHERE id = 1").executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CatalogueRow> catalogueRows() {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT permission_key, state, revoked_at FROM permission")
                .getResultList();
        return rows.stream()
                .map(r -> new CatalogueRow((String) r[0], (String) r[1], r[2] != null))
                .toList();
    }

    @Override
    public long countPermissionBindings(String role) {
        Object count = em.createNativeQuery("SELECT COUNT(*) FROM role_permission WHERE role = :role")
                .setParameter("role", role)
                .getSingleResult();
        return ((Number) count).longValue();
    }
}

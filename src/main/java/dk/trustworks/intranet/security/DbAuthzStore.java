package dk.trustworks.intranet.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
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
        List<String> keys = em.createNativeQuery("""
                SELECT DISTINCT rp.permission_key
                FROM roles r
                JOIN role_permission rp ON rp.role = r.role AND rp.revoked_at IS NULL
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE r.useruuid = :useruuid
                """)
                .setParameter("useruuid", userUuid)
                .getResultList();
        return Set.copyOf(keys);
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

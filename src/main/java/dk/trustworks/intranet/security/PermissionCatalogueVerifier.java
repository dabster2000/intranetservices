package dk.trustworks.intranet.security;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scheduled drift check between {@link Permissions} (the code catalogue) and
 * the {@code permission} table (phase file 4.10).
 *
 * <p><strong>WARN only — this job must never write.</strong> Runtime
 * reconciliation writes are the abandoned boot-reconciler pattern (F-14):
 * concurrently starting ECS tasks would race. Rows that should change state
 * (e.g. an ACTIVE row whose key left the code) are *reported* here and acted
 * on by a human — via Phase 7's admin console once it exists.
 */
@JBossLog
@ApplicationScoped
public class PermissionCatalogueVerifier {

    @Inject
    AuthzStore store;

    @Scheduled(every = "1h", delayed = "10m", identity = "permission-catalogue-verifier")
    void verify() {
        try {
            List<String> divergences = computeDivergences(Permissions.allKeys(), store.catalogueRows());
            if (divergences.isEmpty()) {
                log.debugf("Permission catalogue verifier: code and table agree (%d permissions)",
                        Permissions.allKeys().size());
                return;
            }
            for (String divergence : divergences) {
                log.warnf("Permission catalogue drift: %s", divergence);
            }
        } catch (Exception e) {
            // Never let the verifier take down the scheduler — it is an observer only.
            log.warn("Permission catalogue verifier failed to run", e);
        }
    }

    /**
     * Pure comparison, unit-tested in the fast tier. Returns one human-readable
     * line per divergence; empty when code and table agree.
     */
    static List<String> computeDivergences(Set<String> codeKeys, List<AuthzStore.CatalogueRow> tableRows) {
        List<String> out = new ArrayList<>();

        Set<String> usableTableKeys = new HashSet<>();
        for (AuthzStore.CatalogueRow row : tableRows) {
            if (!row.revoked()) {
                usableTableKeys.add(row.permissionKey());
            }
        }
        for (String key : new TreeSet<>(codeKeys)) {
            if (!usableTableKeys.contains(key)) {
                out.add("'" + key + "' is defined in Permissions.java but has no live row in the permission table"
                        + " — has the V464 seed run, or was the row revoked?");
            }
        }
        for (AuthzStore.CatalogueRow row : tableRows) {
            if (!row.revoked() && "ACTIVE".equals(row.state()) && !codeKeys.contains(row.permissionKey())) {
                out.add("'" + row.permissionKey() + "' is ACTIVE in the permission table but no longer exists in"
                        + " Permissions.java — mark it STALE (the verifier never writes)");
            }
        }
        return out;
    }
}

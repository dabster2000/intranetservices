package dk.trustworks.intranet.security;

import dk.trustworks.intranet.security.AuthzStore.CatalogueRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast-tier tests for the drift comparison behind the Phase 4 scheduled
 * verifier (task 4.10). The comparison is a pure function; the scheduled
 * wrapper only logs WARN lines and — by design — has no write path at all.
 */
class PermissionCatalogueVerifierTest {

    private static CatalogueRow active(String key) {
        return new CatalogueRow(key, "ACTIVE", false);
    }

    @Test
    void agreementProducesNoDivergences() {
        List<String> out = PermissionCatalogueVerifier.computeDivergences(
                Set.of("users:read", "users:write"),
                List.of(active("users:read"), active("users:write")));
        assertEquals(List.of(), out);
    }

    @Test
    void keyInCodeButMissingFromTableIsReported() {
        List<String> out = PermissionCatalogueVerifier.computeDivergences(
                Set.of("users:read", "users:write"),
                List.of(active("users:read")));
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("users:write"), out.get(0));
        assertTrue(out.get(0).contains("no live row"), out.get(0));
    }

    @Test
    void revokedRowCountsAsMissingForACodeKey() {
        List<String> out = PermissionCatalogueVerifier.computeDivergences(
                Set.of("users:read"),
                List.of(new CatalogueRow("users:read", "ACTIVE", true)));
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("users:read"), out.get(0));
    }

    @Test
    void activeRowNoLongerInCodeIsReportedAsStaleCandidate() {
        List<String> out = PermissionCatalogueVerifier.computeDivergences(
                Set.of("users:read"),
                List.of(active("users:read"), active("legacy:scope")));
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("legacy:scope"), out.get(0));
        assertTrue(out.get(0).contains("STALE"), out.get(0));
    }

    @Test
    void staleAndRevokedRowsNotInCodeAreNotReReported() {
        List<String> out = PermissionCatalogueVerifier.computeDivergences(
                Set.of("users:read"),
                List.of(active("users:read"),
                        new CatalogueRow("legacy:scope", "STALE", false),
                        new CatalogueRow("gone:scope", "ACTIVE", true)));
        assertEquals(List.of(), out, "already-STALE and tombstoned rows are settled state, not drift");
    }
}

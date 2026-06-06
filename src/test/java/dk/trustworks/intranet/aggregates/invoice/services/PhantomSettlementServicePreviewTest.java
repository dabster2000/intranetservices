package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settlement preview against the real schema. (previewGroup is lazy-compute, not strictly
 * read-only — it may persist AUTO attributions on first view; these unknown-key/empty-window
 * cases touch no phantoms so no derive runs.) DB-gated — executes only where a MariaDB is
 * present (CI/staging); locally the gate is test-COMPILE (the @QuarkusTest will not boot
 * without a DB, per the project's @QuarkusTest posture).
 *
 * The assertions are SEED-INDEPENDENT invariants so they are meaningful on any database
 * (no false-green placeholder): an unknown settlement-group key produces an empty preview
 * with zero signed totals, and listSettlementGroups over an empty window returns no rows.
 * Running these exercises every native query end-to-end, so a wrong column name or invalid
 * SQL surfaces here rather than only in production.
 */
@QuarkusTest
class PhantomSettlementServicePreviewTest {

    @Inject
    PhantomSettlementService service;

    @Test
    void previewGroup_unknownKey_isEmptyZeroPreview() {
        SettlementGroupKey unknown =
                new SettlementGroupKey("no-such-client", "no-such-company", 1990, 1);

        SettlementGroupPreview p = service.previewGroup(unknown, Set.of());

        assertNotNull(p, "preview is never null");
        assertEquals(unknown, p.key(), "key echoes the requested group");
        assertTrue(p.issuers().isEmpty(), "no phantoms -> no issuer deltas");
        assertEquals(0, p.totalTarget().compareTo(BigDecimal.ZERO), "empty target");
        assertEquals(0, p.totalSettled().compareTo(BigDecimal.ZERO), "empty settled");
        assertEquals(0, p.totalDelta().compareTo(BigDecimal.ZERO), "empty delta");
        assertTrue(p.allResolved(), "no consultants -> vacuously all resolved");
    }

    @Test
    void listSettlementGroups_emptyWindow_returnsNoRows() {
        // A one-day window far in the past contains no phantoms -> no settlement groups.
        List<SettlementGroupRow> rows =
                service.listSettlementGroups(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 1, 2));

        assertNotNull(rows, "rows list is never null");
        assertTrue(rows.isEmpty(), "no phantoms in the window -> no rows");
    }
}

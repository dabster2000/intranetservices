package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the shared settled-side query that moved out of the retired
 * phantom settlement grid into {@link SettlementQueryService}. The settled-side
 * aggregation feeds the self-billed delta math, so its native query must:
 * <ul>
 *   <li>count only live (PENDING_REVIEW/QUEUED/CREATED) internals, never DRAFT;</li>
 *   <li>group the signed Σ(hours*rate) by (issuer company, consultant);</li>
 *   <li>bind the full settlement key (client, debtor company, year, month).</li>
 * </ul>
 *
 * <p>The duplicate-billing guard {@code hasOpenQueuedInternal} (called from
 * {@code SelfBilledSettlementService}) is a static, DB-bound Panache
 * {@code Invoice.count(...)} and has NO direct test — neither here nor in a
 * self-billed DB-gated test. It was preserved byte-identical to its pre-refactor
 * body during the phantom-grid removal; this refactor did not add coverage for it.
 */
class SettlementQueryServiceTest {

    private static final SettlementGroupKey KEY = new SettlementGroupKey("client-1", "debtor-1", 2026, 3);

    @Test
    void settledLinesForGroup_onlyCountsLiveInternals_groupedByIssuerAndConsultant() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(em.createNativeQuery(sql.capture())).thenReturn(query);
        when(query.setParameter(eq("client"), eq("client-1"))).thenReturn(query);
        when(query.setParameter(eq("company"), eq("debtor-1"))).thenReturn(query);
        when(query.setParameter(eq("year"), eq(2026))).thenReturn(query);
        when(query.setParameter(eq("month"), eq(3))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        SettlementQueryService service = new SettlementQueryService();
        service.em = em;

        service.settledLinesForGroup(KEY);

        String emitted = sql.getValue();
        assertTrue(emitted.contains("s.type IN ('INTERNAL','INTERNAL_SERVICE')"),
                "settled side counts both internal flavours");
        assertTrue(emitted.contains("s.status IN ('PENDING_REVIEW','QUEUED','CREATED')"),
                "settled side counts only live internals, never DRAFT");
        assertTrue(emitted.contains("GROUP BY s.companyuuid, ii.consultantuuid"),
                "settled side is keyed by (issuer company, consultant)");
    }

    @Test
    void settledLinesForGroup_mapsRowsToSignedSettledLines() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        when(query.setParameter(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"issuer-A", "cons-1", new BigDecimal("50000.00")},
                new Object[]{"issuer-A", "cons-2", new BigDecimal("-2000.00")}));

        SettlementQueryService service = new SettlementQueryService();
        service.em = em;

        List<SettlementQueryService.SettledLine> lines = service.settledLinesForGroup(KEY);

        assertEquals(2, lines.size());
        assertEquals("issuer-A", lines.get(0).issuerCompanyUuid());
        assertEquals("cons-1", lines.get(0).consultantUuid());
        assertEquals(0, lines.get(0).amount().compareTo(new BigDecimal("50000.00")));
        // Signed: a reversed/credited internal carries through as a negative settled amount.
        assertEquals(0, lines.get(1).amount().compareTo(new BigDecimal("-2000.00")));
    }
}

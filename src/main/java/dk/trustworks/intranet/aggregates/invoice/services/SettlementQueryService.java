package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared read-only settlement queries used by the self-billed settlement flow. These two
 * queries are the single source of truth for the <em>settled side</em> of a settlement group:
 *
 * <ul>
 *   <li>{@link #settledLinesForGroup} — aggregates what has already been settled for a group,
 *       keyed by (issuer company, consultant). The self-billed delta math
 *       ({@code SelfBilledDeltaQuery}) subtracts this from the HUMAN-assigned target to know how
 *       much (if anything) still needs to be booked.</li>
 *   <li>{@link #hasOpenQueuedInternal} — the duplicate-settlement guard. It reports whether an
 *       un-finalized QUEUED internal already exists for a (group, issuer), so a second settle of
 *       the same group/issuer is rejected ({@code SelfBilledSettlementService}) rather than
 *       creating a duplicate document.</li>
 * </ul>
 *
 * <p>Extracted verbatim from the retired phantom work-value settlement grid: the consolidated
 * grid is dead (production has zero settlement groups outside self-billed scope), but these two
 * queries are still the shared settled-side aggregation + duplicate guard the workbench depends on.
 */
@ApplicationScoped
public class SettlementQueryService {

    @Inject
    EntityManager em;

    /**
     * One settled (issuer, consultant) aggregate row for a settlement group: the signed
     * Σ(hours*rate) of that issuer/consultant's live internal lines. Signed throughout, so a
     * reversed/credited internal carries through as a negative settled amount. Pure value type
     * (no DB, no CDI) — consumed by {@code SelfBilledDeltaQuery} to compute the per-consultant delta.
     */
    public record SettledLine(String issuerCompanyUuid, String consultantUuid, BigDecimal amount) {}

    /** Settled per (issuer, consultant) from the group's live internals' lines (signed). */
    @Transactional
    public List<SettledLine> settledLinesForGroup(SettlementGroupKey key) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.companyuuid AS issuer, ii.consultantuuid AS consultant,
                       COALESCE(SUM(ii.hours*ii.rate),0) AS amount
                FROM invoices s
                JOIN invoiceitems ii ON ii.invoiceuuid = s.uuid
                WHERE s.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND s.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND s.settlement_billing_client_uuid = :client
                  AND s.settlement_debtor_companyuuid = :company
                  AND s.settlement_year = :year AND s.settlement_month = :month
                  AND ii.consultantuuid IS NOT NULL
                GROUP BY s.companyuuid, ii.consultantuuid
                """)
                .setParameter("client", key.billingClientUuid())
                .setParameter("company", key.debtorCompanyUuid())
                .setParameter("year", key.year())
                .setParameter("month", key.month())
                .getResultList();
        List<SettledLine> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new SettledLine((String) r[0], (String) r[1], toBig(r[2])));
        }
        return out;
    }

    /**
     * True if an un-finalized QUEUED internal already exists for (group, issuer) — prevents
     * duplicate settlement of the same issuer. REQUIRES_NEW so a call from a non-transactional
     * settle flow sees a fresh snapshot after a prior issuer's createSettlementInternal commit.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean hasOpenQueuedInternal(SettlementGroupKey key, String issuerCompanyUuid) {
        return Invoice.count("type in ?1 and status = ?2 and settlementBillingClientUuid = ?3 "
                        + "and settlementDebtorCompanyuuid = ?4 and settlementYear = ?5 and settlementMonth = ?6 "
                        + "and company.uuid = ?7",
                List.of(InvoiceType.INTERNAL, InvoiceType.INTERNAL_SERVICE), InvoiceStatus.QUEUED,
                key.billingClientUuid(), key.debtorCompanyUuid(), key.year(), key.month(), issuerCompanyUuid) > 0;
    }

    /** Coerce a native-query numeric cell to BigDecimal (null -> ZERO; passthrough BigDecimal; else via double). */
    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return (o instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) o).doubleValue());
    }
}

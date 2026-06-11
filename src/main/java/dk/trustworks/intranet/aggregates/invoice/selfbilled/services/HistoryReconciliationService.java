package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.model.AttributionAuditLog;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.HistoryRow;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.LinkRequest;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.UnlinkedInternalRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * History (AC5) + legacy linking (AC8). History: stamped internals whose booked
 * total != the assigned self-billed total for the same (consultant, work-period) —
 * exact matches auto-clear; corrections go through the normal settle path (human-
 * initiated top-up / credit note). Linking: a loose READ-ONLY discovery query
 * proposes unstamped cross-company internals; a human link stamps the settlement
 * key + item consultant (never guessed) and writes an AttributionAuditLog row (AC7).
 */
@ApplicationScoped
public class HistoryReconciliationService {

    private static final Logger log = Logger.getLogger(HistoryReconciliationService.class);
    private static final BigDecimal MATCH_TOLERANCE = BigDecimal.ONE;

    @Inject EntityManager em;
    @Inject SelfBilledDeltaQuery deltaQuery;

    public record BookedGroup(String consultantUuid, int workYear, int workMonth,
                              BigDecimal booked, List<String> internalUuids) {}

    /** Pure mismatch fold: booked groups vs assigned totals keyed "consultant|year|month". */
    static List<HistoryRow> matchRows(List<BookedGroup> booked, Map<String, BigDecimal> assignedByKey) {
        List<HistoryRow> out = new ArrayList<>();
        for (BookedGroup b : booked) {
            BigDecimal assigned = assignedByKey.getOrDefault(
                    b.consultantUuid() + "|" + b.workYear() + "|" + b.workMonth(), BigDecimal.ZERO);
            BigDecimal delta = assigned.subtract(b.booked());
            if (delta.abs().compareTo(MATCH_TOLERANCE) <= 0) continue;   // AC5 auto-clear
            out.add(new HistoryRow(b.consultantUuid(), null, b.workYear(), b.workMonth(),
                    b.booked().doubleValue(), assigned.doubleValue(), delta.doubleValue(),
                    b.internalUuids()));
        }
        return out;
    }

    /** History queue for client + work window [fromYm,toYm]. Names resolved; masking at the resource. */
    @Transactional
    public List<HistoryRow> historyRows(String clientUuid, int fromYm, int toYm) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ii.consultantuuid, s.settlement_year, s.settlement_month,
                       COALESCE(SUM(ii.hours*ii.rate), 0),
                       GROUP_CONCAT(DISTINCT s.uuid)
                FROM invoices s
                JOIN invoiceitems ii ON ii.invoiceuuid = s.uuid
                WHERE s.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND s.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND s.settlement_billing_client_uuid = :c
                  AND (s.settlement_year*100 + s.settlement_month) BETWEEN :from AND :to
                  AND ii.consultantuuid IS NOT NULL
                GROUP BY ii.consultantuuid, s.settlement_year, s.settlement_month
                """).setParameter("c", clientUuid).setParameter("from", fromYm).setParameter("to", toYm)
                .getResultList();

        List<BookedGroup> booked = new ArrayList<>(rows.size());
        Map<String, BigDecimal> assigned = new HashMap<>();
        for (Object[] r : rows) {
            String consultant = (String) r[0];
            int y = ((Number) r[1]).intValue(), m = ((Number) r[2]).intValue();
            booked.add(new BookedGroup(consultant, y, m,
                    toBig(r[3]).setScale(2, RoundingMode.HALF_UP),
                    List.of(((String) r[4]).split(","))));
            assigned.put(consultant + "|" + y + "|" + m, deltaQuery.target(clientUuid, consultant, y, m));
        }
        List<HistoryRow> out = matchRows(booked, assigned);

        Set<String> ids = new HashSet<>();
        out.forEach(h -> ids.add(h.consultantUuid()));
        Map<String, String> names = consultantNames(ids);
        return out.stream().map(h -> new HistoryRow(h.consultantUuid(),
                names.getOrDefault(h.consultantUuid(), h.consultantUuid()),
                h.workYear(), h.workMonth(), h.booked(), h.assigned(), h.proposedDelta(),
                h.internalUuids())).toList();
    }

    /**
     * Loose read-only discovery (spec §6.2/§8): unstamped live cross-company internals to in-scope debtors,
     * NARROWED (Feature 2a) to internals that are self-billed-related.
     *
     * <p>Scope (security review L-1): still spans ALL enabled self-billed clients — an unlinked internal
     * has no settlement key yet, so it carries no client linkage until a human links it, and enabled
     * sources can share a debtor company. The queue therefore stays scoped only to enabled self-billed
     * debtors ({@code selfbilled_source.enabled = 1}). On top of that the Feature 2a filter EXCLUDES any
     * internal whose referenced source invoice belongs to a work client that is NOT an enabled self-billed
     * source. An internal is kept when it has no source ref ({@code invoice_ref_uuid IS NULL}), its source
     * row is missing, OR its source resolves to an enabled self-billed client by either rule below:
     * <ul>
     *   <li>an INVOICE source's REAL work client is its {@code project.clientuuid} (joined via
     *       {@code src.projectuuid}); an INVOICE's {@code billing_client_uuid} is the contract billing
     *       entity (e.g. a broker), never the work client, so it is NOT used for INVOICE sources;</li>
     *   <li>a PHANTOM source has no project but carries its synthetic self-billed client in
     *       {@code billing_client_uuid} — kept as the phantom-shaped fallback.</li>
     * </ul>
     * The {@code invoices} table has NO {@code clientuuid} column (only {@code billing_client_uuid} and the
     * denormalized {@code clientname}); the work client is reachable only through the project join — using
     * {@code src.clientuuid} would throw SQL 1054. Consultant identity is masked downstream for callers
     * lacking the {@code users:read} scope.
     *
     * <p>Prefill (Feature 2b): the suggested consultant is the internal's own item consultant when ALL
     * items carry the same non-null {@code consultantuuid} (else null — never guessed), and the suggested
     * work period is the source invoice's {@code year}/{@code month} when the source exists (else null).
     *
     * <p>Cancelled-internal exclusion: any internal referenced by a LIVE credit note
     * ({@code creditnote_for_uuid = i.uuid}, {@code type = 'CREDIT_NOTE'}, status in
     * PENDING_REVIEW/QUEUED/CREATED) is void and dropped — linking it would stamp a cancelled document
     * into settled totals, producing phantom mismatches (prod-observed: 10 such credited internals matched
     * the queue). A DRAFT credit note does not yet void the internal. ANY live credit note voids it; the
     * full-amount credit note is the observed reality, so partial credits are NOT distinguished here.
     */
    @Transactional
    public List<UnlinkedInternalRow> unlinkedInternals() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT i.uuid, i.invoicenumber, i.type, i.status, ic.name, dc.name, i.invoicedate,
                       i.specificdescription, COALESCE(SUM(ii.hours*ii.rate), 0),
                       GROUP_CONCAT(DISTINCT ii.itemname SEPARATOR ' | '),
                       CASE WHEN COUNT(DISTINCT ii.consultantuuid) = 1 THEN MAX(ii.consultantuuid) END,
                       src.year, src.month
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                LEFT JOIN companies ic ON ic.uuid = i.companyuuid
                LEFT JOIN companies dc ON dc.uuid = i.debtor_companyuuid
                LEFT JOIN invoices src ON src.uuid = i.invoice_ref_uuid
                LEFT JOIN project p ON p.uuid = src.projectuuid
                WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND i.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND i.settlement_year IS NULL
                  AND i.companyuuid <> i.debtor_companyuuid
                  AND i.debtor_companyuuid IN (SELECT agreement_company_uuid FROM selfbilled_source WHERE enabled = 1)
                  AND (i.invoice_ref_uuid IS NULL
                       OR src.uuid IS NULL
                       OR p.clientuuid IN (SELECT client_uuid FROM selfbilled_source WHERE enabled = 1)
                       OR src.billing_client_uuid IN (SELECT client_uuid FROM selfbilled_source WHERE enabled = 1))
                  AND NOT EXISTS (SELECT 1 FROM invoices cn
                                  WHERE cn.creditnote_for_uuid = i.uuid
                                    AND cn.type = 'CREDIT_NOTE'
                                    AND cn.status IN ('PENDING_REVIEW','QUEUED','CREATED'))
                GROUP BY i.uuid, i.invoicenumber, i.type, i.status, ic.name, dc.name, i.invoicedate,
                         i.specificdescription, src.year, src.month
                ORDER BY i.invoicedate DESC
                """).getResultList();
        List<UnlinkedInternalRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String consultant = (String) r[10];   // null unless all items share one consultant
            Integer year = r[11] == null ? null : ((Number) r[11]).intValue();
            Integer month = r[12] == null ? null : ((Number) r[12]).intValue();
            out.add(new UnlinkedInternalRow((String) r[0], r[1] == null ? 0 : ((Number) r[1]).intValue(),
                    (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    r[6] == null ? null : r[6].toString(), (String) r[7],
                    toBig(r[8]).doubleValue(),
                    r[9] == null ? List.of() : List.of(((String) r[9]).split(" \\| ")),
                    consultant, null /* name filled below */, year, month));
        }
        Set<String> ids = new HashSet<>();
        out.forEach(u -> { if (u.suggestedConsultantUuid() != null) ids.add(u.suggestedConsultantUuid()); });
        Map<String, String> names = consultantNames(ids);
        return out.stream().map(u -> new UnlinkedInternalRow(u.invoiceUuid(), u.invoicenumber(), u.type(),
                u.status(), u.issuerCompanyName(), u.debtorCompanyName(), u.invoicedate(), u.description(),
                u.total(), u.itemNames(), u.suggestedConsultantUuid(),
                u.suggestedConsultantUuid() == null ? null : names.get(u.suggestedConsultantUuid()),
                u.suggestedWorkYear(), u.suggestedWorkMonth())).toList();
    }

    /**
     * Count only — feeds the Consultants-tab "unlinked candidates exist" warning (AC8). Mirrors the
     * {@link #unlinkedInternals()} discovery scope INCLUDING the live-credit-note exclusion, so the banner
     * count stays in parity with the queue: a credited (void) internal is hidden from both, never leaving
     * the banner showing a non-zero count over an empty queue with an unfollowable instruction.
     */
    @Transactional
    public int unlinkedCandidateCount() {
        Object v = em.createNativeQuery("""
                SELECT COUNT(DISTINCT i.uuid) FROM invoices i
                LEFT JOIN invoices src ON src.uuid = i.invoice_ref_uuid
                LEFT JOIN project p ON p.uuid = src.projectuuid
                WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND i.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND i.settlement_year IS NULL
                  AND i.companyuuid <> i.debtor_companyuuid
                  AND i.debtor_companyuuid IN (SELECT agreement_company_uuid FROM selfbilled_source WHERE enabled = 1)
                  AND (i.invoice_ref_uuid IS NULL
                       OR src.uuid IS NULL
                       OR p.clientuuid IN (SELECT client_uuid FROM selfbilled_source WHERE enabled = 1)
                       OR src.billing_client_uuid IN (SELECT client_uuid FROM selfbilled_source WHERE enabled = 1))
                  AND NOT EXISTS (SELECT 1 FROM invoices cn
                                  WHERE cn.creditnote_for_uuid = i.uuid
                                    AND cn.type = 'CREDIT_NOTE'
                                    AND cn.status IN ('PENDING_REVIEW','QUEUED','CREATED'))
                """).getSingleResult();
        return ((Number) v).intValue();
    }

    /**
     * Human link (AC8): stamp the settlement key + item-level consultant onto a
     * pre-existing internal, making it visible to settled(g) and history. 409 if
     * already stamped or if items carry a DIFFERENT consultant. Audited (AC7).
     */
    @Transactional
    public void linkInternal(String invoiceUuid, LinkRequest req, String actor) {
        Invoice internal = Invoice.findById(invoiceUuid);
        if (internal == null) throw new WebApplicationException("Internal not found", Response.Status.NOT_FOUND);
        if (internal.getType() != InvoiceType.INTERNAL && internal.getType() != InvoiceType.INTERNAL_SERVICE) {
            throw new WebApplicationException("Not an internal invoice", Response.Status.BAD_REQUEST);
        }
        // Belt-and-braces (public method): a 0/out-of-range work period would stamp settlement_year=0,
        // month=0 — silently removing the internal from the unlinked queue while never counting toward
        // any real period's settled(), so the Consultants tab keeps the delta and the next settle double-books.
        if (req.workYear() < 1 || req.workYear() > 9999 || req.workMonth() < 1 || req.workMonth() > 12) {
            throw new WebApplicationException("workYear and workMonth are required (workMonth 1-12)",
                    Response.Status.BAD_REQUEST);
        }
        if (internal.getSettlementYear() != null) {
            throw new WebApplicationException("Internal already carries a settlement stamp",
                    Response.Status.CONFLICT);
        }
        String debtor = deltaQuery.debtorFor(req.clientUuid());
        if (debtor == null) {
            throw new WebApplicationException("Client is not a configured self-billed source",
                    Response.Status.BAD_REQUEST);
        }
        // Stamp must equal the client's agreement company, or settled() (filters client+debtor)
        // never counts this internal while historyRows (client only) shows it — the Consultants
        // tab would keep an unsettled delta and the next settle books a duplicate.
        if (!debtor.equals(internal.getDebtorCompanyuuid())) {
            throw new WebApplicationException("Internal's debtor company does not match the client's agreement company",
                    Response.Status.CONFLICT);
        }
        // Same live-statuses allowlist as the discovery query — a DRAFT internal would be
        // stamped yet never counted by settled().
        if (internal.getStatus() != InvoiceStatus.PENDING_REVIEW
                && internal.getStatus() != InvoiceStatus.QUEUED
                && internal.getStatus() != InvoiceStatus.CREATED) {
            throw new WebApplicationException("Internal is not in a live status (PENDING_REVIEW/QUEUED/CREATED) — cannot link",
                    Response.Status.CONFLICT);
        }
        // A live credit note voids the internal: linking a cancelled document would stamp it into
        // settled totals (phantom mismatch). Mirrors the discovery query's NOT EXISTS — protects the
        // direct API path. ANY live credit note voids it; partial credits are not distinguished (DRAFT
        // credit notes do not yet void). 409.
        if (cancelledByLiveCreditNote(invoiceUuid)) {
            throw new WebApplicationException(
                    "Internal #" + internal.getInvoicenumber()
                            + " has been cancelled by a credit note and cannot be linked",
                    Response.Status.CONFLICT);
        }
        List<InvoiceItem> items = internal.getInvoiceitems();
        if (items == null || items.isEmpty()) {
            throw new WebApplicationException("Internal has no items to stamp", Response.Status.CONFLICT);
        }
        for (InvoiceItem item : items) {
            if (item.consultantuuid != null && !item.consultantuuid.isBlank()
                    && !item.consultantuuid.equals(req.consultantUuid())) {
                throw new WebApplicationException("Items carry a different consultant — cannot link",
                        Response.Status.CONFLICT);
            }
        }
        String oldState = "{\"settlementYear\":null,\"itemConsultants\":\"" +
                items.stream().map(i -> String.valueOf(i.consultantuuid)).reduce((a, b) -> a + "," + b).orElse("") + "\"}";

        internal.setSettlementBillingClientUuid(req.clientUuid());
        internal.setSettlementDebtorCompanyuuid(internal.getDebtorCompanyuuid());
        internal.setSettlementYear(req.workYear());
        internal.setSettlementMonth(req.workMonth());
        for (InvoiceItem item : items) {
            if (item.consultantuuid == null || item.consultantuuid.isBlank()) {
                item.consultantuuid = req.consultantUuid();
            }
        }
        String newState = "{\"settlementKey\":\"" + req.clientUuid() + "|" + internal.getDebtorCompanyuuid()
                + "|" + req.workYear() + "|" + req.workMonth() + "\",\"consultant\":\"" + req.consultantUuid() + "\"}";
        new AttributionAuditLog(invoiceUuid, items.get(0).uuid, actor, "SELFBILLED_LINK",
                oldState, newState, "Workbench link of pre-existing internal").persist();
        log.infof("linkInternal: %s -> (%s, %s, %d-%02d) by=%s",
                invoiceUuid, req.clientUuid(), req.consultantUuid(), req.workYear(), req.workMonth(), actor);
    }

    /** True when a LIVE credit note (PENDING_REVIEW/QUEUED/CREATED) references this internal — voids it for linking. */
    private boolean cancelledByLiveCreditNote(String invoiceUuid) {
        Object v = em.createNativeQuery("""
                SELECT COUNT(*) FROM invoices cn
                WHERE cn.creditnote_for_uuid = :uuid
                  AND cn.type = 'CREDIT_NOTE'
                  AND cn.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                """).setParameter("uuid", invoiceUuid).getSingleResult();
        return ((Number) v).intValue() > 0;
    }

    private Map<String, String> consultantNames(Set<String> ids) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT uuid, CONCAT(firstname,' ',lastname) FROM user WHERE uuid IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return (o instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) o).doubleValue());
    }
}

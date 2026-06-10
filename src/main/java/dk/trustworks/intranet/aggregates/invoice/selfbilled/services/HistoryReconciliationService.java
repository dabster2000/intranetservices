package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.model.AttributionAuditLog;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
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

    /** Loose read-only discovery (spec §6.2/§8): unstamped live cross-company internals to in-scope debtors. */
    @Transactional
    public List<UnlinkedInternalRow> unlinkedInternals() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT i.uuid, i.invoicenumber, i.type, i.status, ic.name, dc.name, i.invoicedate,
                       i.specificdescription, COALESCE(SUM(ii.hours*ii.rate), 0),
                       GROUP_CONCAT(DISTINCT ii.itemname SEPARATOR ' | ')
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                LEFT JOIN companies ic ON ic.uuid = i.companyuuid
                LEFT JOIN companies dc ON dc.uuid = i.debtor_companyuuid
                WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND i.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND i.settlement_year IS NULL
                  AND i.companyuuid <> i.debtor_companyuuid
                  AND i.debtor_companyuuid IN (SELECT agreement_company_uuid FROM selfbilled_source WHERE enabled = 1)
                GROUP BY i.uuid, i.invoicenumber, i.type, i.status, ic.name, dc.name, i.invoicedate, i.specificdescription
                ORDER BY i.invoicedate DESC
                """).getResultList();
        List<UnlinkedInternalRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new UnlinkedInternalRow((String) r[0], r[1] == null ? 0 : ((Number) r[1]).intValue(),
                    (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    r[6] == null ? null : r[6].toString(), (String) r[7],
                    toBig(r[8]).doubleValue(),
                    r[9] == null ? List.of() : List.of(((String) r[9]).split(" \\| "))));
        }
        return out;
    }

    /** Count only — feeds the Consultants-tab "unlinked candidates exist" warning (AC8). */
    @Transactional
    public int unlinkedCandidateCount() {
        Object v = em.createNativeQuery("""
                SELECT COUNT(*) FROM invoices i
                WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND i.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND i.settlement_year IS NULL
                  AND i.companyuuid <> i.debtor_companyuuid
                  AND i.debtor_companyuuid IN (SELECT agreement_company_uuid FROM selfbilled_source WHERE enabled = 1)
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
        if (internal.getSettlementYear() != null) {
            throw new WebApplicationException("Internal already carries a settlement stamp",
                    Response.Status.CONFLICT);
        }
        String debtor = deltaQuery.debtorFor(req.clientUuid());
        if (debtor == null) {
            throw new WebApplicationException("Client is not a configured self-billed source",
                    Response.Status.BAD_REQUEST);
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

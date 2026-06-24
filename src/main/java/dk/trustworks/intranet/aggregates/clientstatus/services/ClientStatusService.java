package dk.trustworks.intranet.aggregates.clientstatus.services;

import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusMath;
import dk.trustworks.intranet.aggregates.clientstatus.dto.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@JBossLog
@ApplicationScoped
public class ClientStatusService {

    private static final String INTERNAL_CLIENT_UUID = "40c93307-1dfa-405a-8211-37cbda75318b";

    @Inject
    EntityManager em;

    private static double num(Object value) {
        return value == null ? 0d : ((Number) value).doubleValue();
    }

    /** Build the full per-client-per-month grid for the TTM window ending at {@code end}. */
    public ClientStatusResponse getClientStatus(YearMonth end) {
        List<String> months = ClientStatusMath.ttmMonthKeys(end);
        LocalDate fromDate = ClientStatusMath.ttmFromDate(end);
        LocalDate toDate = ClientStatusMath.ttmToDateExclusive(end);
        int fromPeriod = ClientStatusMath.ttmFromPeriod(end);
        int toPeriod = ClientStatusMath.ttmToPeriod(end);

        // client -> (monthKey -> expected)
        Map<String, Map<String, Double>> expectedByClient = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> expectedRows = em.createNativeQuery("""
                SELECT w.clientuuid AS client_id,
                       DATE_FORMAT(w.registered, '%Y%m') AS month_key,
                       SUM(IFNULL(w.rate,0) * w.workduration) AS expected
                FROM work_full w
                WHERE w.rate > 0
                  AND w.clientuuid IS NOT NULL
                  AND w.clientuuid <> :internalClient
                  AND w.registered >= :fromDate AND w.registered < :toDate
                GROUP BY w.clientuuid, DATE_FORMAT(w.registered, '%Y%m')
                """, Tuple.class)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();
        for (Tuple r : expectedRows) {
            expectedByClient
                    .computeIfAbsent((String) r.get("client_id"), k -> new HashMap<>())
                    .put((String) r.get("month_key"), num(r.get("expected")));
        }

        // client -> (monthKey -> invoiced), GROSS (full-rate) basis, computed directly from
        // invoices/invoiceitems (the fact table is CREATED-only and cannot include QUEUED):
        //  - status IN (CREATED, QUEUED): booked + committed-to-book billing. Raw DRAFT is excluded by design.
        //  - type INVOICE/PHANTOM count positive (incl. self-billed/e-conomic PHANTOMs), CREDIT_NOTE negative;
        //    INTERNAL/INTERNAL_SERVICE excluded.
        //  - GROSS: discounts/fees are negative non-consultant lines (framework "trapperabat", pre-agreed
        //    discounts, admin fees) NOT written back to work_full, so they are dropped (set to 0) to match
        //    the gross "expected"; positive non-consultant lines (self-billed PHANTOMs, fixed-price) are kept.
        //  - client via COALESCE(project.clientuuid, invoices.billing_client_uuid): normal invoices
        //    resolve through their project; self-billed/e-conomic PHANTOMs have projectuuid=NULL and
        //    carry the client on billing_client_uuid (stamped by PhantomAttributionService). Without
        //    this fallback the project JOIN drops every phantom and self-billed clients (Vattenfall,
        //    Energinet) show a false under-billing gap equal to their entire self-billed revenue.
        //  - month via the billing period (invoice.year + invoice.month), NOT invoicedate —
        //    invoicedate is the issue date (usually the month AFTER the work) and would mis-align
        //    "invoiced" against work-month "expected". See docs/finalized/invoicing/concepts-and-terminology.md §6.
        Map<String, Map<String, Double>> invoicedByClient = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> invoicedRows = em.createNativeQuery("""
                SELECT COALESCE(p.clientuuid, i.billing_client_uuid) AS client_id,
                       CONCAT(i.year, LPAD(i.month, 2, '0')) AS month_key,
                       SUM(CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END
                           * CASE WHEN ii.consultantuuid IS NULL AND (ii.hours * ii.rate) < 0
                                  THEN 0 ELSE (ii.hours * ii.rate) END) AS invoiced
                FROM invoices i
                LEFT JOIN project p ON p.uuid = i.projectuuid
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.status IN ('CREATED','QUEUED')
                  AND i.type IN ('INVOICE','PHANTOM','CREDIT_NOTE')
                  AND COALESCE(p.clientuuid, i.billing_client_uuid) IS NOT NULL
                  AND COALESCE(p.clientuuid, i.billing_client_uuid) <> :internalClient
                  AND (i.year * 100 + i.month) >= :fromPeriod
                  AND (i.year * 100 + i.month) <= :toPeriod
                GROUP BY COALESCE(p.clientuuid, i.billing_client_uuid), CONCAT(i.year, LPAD(i.month, 2, '0'))
                """, Tuple.class)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fromPeriod", fromPeriod)
                .setParameter("toPeriod", toPeriod)
                .getResultList();
        for (Tuple r : invoicedRows) {
            invoicedByClient
                    .computeIfAbsent((String) r.get("client_id"), k -> new HashMap<>())
                    .put((String) r.get("month_key"), num(r.get("invoiced")));
        }

        // Union of client uuids that have any activity.
        Set<String> clientUuids = new HashSet<>();
        clientUuids.addAll(expectedByClient.keySet());
        clientUuids.addAll(invoicedByClient.keySet());
        if (clientUuids.isEmpty()) {
            return new ClientStatusResponse(months, List.of(),
                    new ClientStatusSummary(0, 0, 0, 0, 0, 0));
        }

        // Resolve names + segments.
        Map<String, String[]> nameSegByUuid = new HashMap<>(); // uuid -> [name, segment]
        @SuppressWarnings("unchecked")
        List<Tuple> clientRows = em.createNativeQuery("""
                SELECT c.uuid, c.name, c.segment
                FROM client c
                WHERE c.uuid IN (:uuids)
                """, Tuple.class)
                .setParameter("uuids", clientUuids)
                .getResultList();
        for (Tuple r : clientRows) {
            nameSegByUuid.put((String) r.get("uuid"),
                    new String[]{(String) r.get("name"), (String) r.get("segment")});
        }

        // The current month is never fully invoiced until early in the following month, so any
        // still-provisional month is shown in the heatmap but excluded from every summation
        // (row totals, gaps, outstanding, and the under/fully-billed classification).
        Set<String> provisional = ClientStatusMath.provisionalMonthKeys(months, LocalDate.now());

        List<ClientStatusRow> rows = new ArrayList<>(clientUuids.size());
        double totalExpected = 0, totalInvoiced = 0, outstanding = 0;
        int underBilled = 0, fullyBilled = 0;

        for (String uuid : clientUuids) {
            Map<String, Double> exp = expectedByClient.getOrDefault(uuid, Map.of());
            Map<String, Double> inv = invoicedByClient.getOrDefault(uuid, Map.of());
            String[] nameSeg = nameSegByUuid.getOrDefault(uuid, new String[]{uuid, null});

            List<ClientStatusCell> cells = new ArrayList<>(12);
            double rowExpected = 0, rowInvoiced = 0;
            int gaps = 0;
            for (String mk : months) {
                double e = exp.getOrDefault(mk, 0d);
                double i = inv.getOrDefault(mk, 0d);
                ClientStatusCellState state = ClientStatusMath.classify(e, i);
                cells.add(new ClientStatusCell(mk, e, i, i - e, state));
                if (provisional.contains(mk)) continue; // visible cell, but not yet counted in totals
                rowExpected += e;
                rowInvoiced += i;
                if (state == ClientStatusCellState.NOT_INVOICED || state == ClientStatusCellState.PARTIAL) gaps++;
                if (e - i > 0) outstanding += (e - i);
            }
            rows.add(new ClientStatusRow(uuid, nameSeg[0], nameSeg[1], cells,
                    rowExpected, rowInvoiced, rowInvoiced - rowExpected, gaps));
            totalExpected += rowExpected;
            totalInvoiced += rowInvoiced;
            if (rowExpected > 0d) {
                if (rowInvoiced / rowExpected < 0.98d) underBilled++;
                else fullyBilled++;
            }
        }

        rows.sort(Comparator.comparingDouble(ClientStatusRow::delta)); // most under-billed first
        ClientStatusSummary summary = new ClientStatusSummary(
                totalExpected, totalInvoiced, outstanding, underBilled, fullyBilled, rows.size());
        return new ClientStatusResponse(months, rows, summary);
    }

    /** Drill-down: registered work (by consultant × project) and invoices for one client-month. */
    public ClientStatusDetailResponse getClientStatusDetail(String clientUuid, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate fromDate = ym.atDay(1);
        LocalDate toDate = ym.plusMonths(1).atDay(1);

        @SuppressWarnings("unchecked")
        List<Tuple> workRows = em.createNativeQuery("""
                SELECT w.useruuid AS user_id,
                       COALESCE(CONCAT(u.firstname, ' ', u.lastname), w.useruuid) AS consultant_name,
                       w.projectuuid AS project_id,
                       COALESCE(p.name, '') AS project_name,
                       SUM(w.workduration) AS hours,
                       AVG(w.rate) AS avg_rate,
                       SUM(IFNULL(w.rate,0) * w.workduration) AS value
                FROM work_full w
                LEFT JOIN `user` u ON u.uuid = w.useruuid
                LEFT JOIN project p ON p.uuid = w.projectuuid
                WHERE w.clientuuid = :client
                  AND w.rate > 0
                  AND w.registered >= :fromDate AND w.registered < :toDate
                GROUP BY w.useruuid, consultant_name, w.projectuuid, project_name
                ORDER BY value DESC
                """, Tuple.class)
                .setParameter("client", clientUuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        List<ClientStatusWorkLine> work = new ArrayList<>(workRows.size());
        double expected = 0d;
        for (Tuple r : workRows) {
            double value = num(r.get("value"));
            expected += value;
            work.add(new ClientStatusWorkLine(
                    (String) r.get("user_id"),
                    (String) r.get("consultant_name"),
                    (String) r.get("project_id"),
                    (String) r.get("project_name"),
                    num(r.get("hours")),
                    num(r.get("avg_rate")),
                    value));
        }

        @SuppressWarnings("unchecked")
        List<Tuple> invoiceRows = em.createNativeQuery("""
                SELECT i.uuid AS invoice_uuid,
                       i.invoicenumber AS invoice_number,
                       i.type AS type,
                       i.status AS status,
                       i.invoicedate AS invoicedate,
                       COALESCE(CASE WHEN i.type = 'CREDIT_NOTE'
                                     THEN -SUM(ii.hours * ii.rate)
                                     ELSE SUM(ii.hours * ii.rate) END, 0) AS amount
                FROM invoices i
                LEFT JOIN project p ON p.uuid = i.projectuuid
                LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE COALESCE(p.clientuuid, i.billing_client_uuid) = :client
                  AND i.year = :year AND i.month = :month
                  AND i.status IN ('CREATED','QUEUED')
                  AND i.type IN ('INVOICE','PHANTOM','CREDIT_NOTE')
                GROUP BY i.uuid, i.invoicenumber, i.type, i.status, i.invoicedate
                ORDER BY i.invoicenumber
                """, Tuple.class)
                .setParameter("client", clientUuid)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList();

        List<ClientStatusInvoiceLine> invoices = new ArrayList<>(invoiceRows.size());
        for (Tuple r : invoiceRows) {
            double amount = num(r.get("amount"));
            Object invDate = r.get("invoicedate");
            invoices.add(new ClientStatusInvoiceLine(
                    (String) r.get("invoice_uuid"),
                    ((Number) r.get("invoice_number")).intValue(),
                    (String) r.get("type"),
                    (String) r.get("status"),
                    amount,
                    invDate == null ? null : invDate.toString()));
        }

        // Headline "invoiced" — GROSS, CREATED+QUEUED, same basis + source as the grid.
        @SuppressWarnings("unchecked")
        List<Tuple> headRows = em.createNativeQuery("""
                SELECT COALESCE(SUM(CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END
                       * CASE WHEN ii.consultantuuid IS NULL AND (ii.hours * ii.rate) < 0
                              THEN 0 ELSE (ii.hours * ii.rate) END), 0) AS invoiced
                FROM invoices i
                LEFT JOIN project p ON p.uuid = i.projectuuid
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.status IN ('CREATED','QUEUED')
                  AND i.type IN ('INVOICE','PHANTOM','CREDIT_NOTE')
                  AND COALESCE(p.clientuuid, i.billing_client_uuid) = :client
                  AND i.year = :year AND i.month = :month
                """, Tuple.class)
                .setParameter("client", clientUuid)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList();
        double invoicedHeadline = headRows.isEmpty() ? 0d : num(headRows.get(0).get("invoiced"));

        return new ClientStatusDetailResponse(
                clientUuid, resolveClientName(clientUuid), year, month,
                expected, invoicedHeadline, invoicedHeadline - expected,
                ClientStatusMath.classify(expected, invoicedHeadline),
                work, invoices);
    }

    private String resolveClientName(String clientUuid) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(
                "SELECT c.name FROM client c WHERE c.uuid = :uuid", Tuple.class)
                .setParameter("uuid", clientUuid)
                .getResultList();
        return rows.isEmpty() ? clientUuid : (String) rows.get(0).get("name");
    }
}

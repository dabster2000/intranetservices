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
        String fromKey = months.get(0);
        String toKey = months.get(months.size() - 1);

        // client -> (monthKey -> expected)
        Map<String, Map<String, Double>> expectedByClient = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> expectedRows = em.createNativeQuery("""
                SELECT w.clientuuid AS client_id,
                       DATE_FORMAT(w.registered, '%Y%m') AS month_key,
                       SUM(IFNULL(w.rate,0) * w.workduration
                           * IF(w.discount > 0, 1.0 - (w.discount/100.0), 1)) AS expected
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

        // client -> (monthKey -> invoiced)
        Map<String, Map<String, Double>> invoicedByClient = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> invoicedRows = em.createNativeQuery("""
                SELECT f.client_id, f.month_key, SUM(f.net_revenue_dkk) AS invoiced
                FROM fact_client_revenue_mat f
                WHERE f.month_key BETWEEN :fromKey AND :toKey
                  AND f.client_id IS NOT NULL
                  AND f.client_id <> :internalClient
                GROUP BY f.client_id, f.month_key
                """, Tuple.class)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
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
}

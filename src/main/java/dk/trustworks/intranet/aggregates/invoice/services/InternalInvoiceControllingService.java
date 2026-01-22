package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.ClientWithInternalsDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.utils.NumberUtils.round2;

@JBossLog
@ApplicationScoped
public class InternalInvoiceControllingService {

    @Inject
    EntityManager em;


    /**
     * Find invoices where the invoice is issued by one company but contains invoice items
     * with consultants from another company. This is determined by checking the consultant's
     * latest userstatus on or before the invoice date ("as-of"), and comparing the
     * consultant company to the issuing invoice company.
     *
     * Implementation notes:
     * - Uses MariaDB 11 window functions to pick the latest status per (invoice, consultant)
     *   efficiently, avoiding correlated subqueries.
     * - Date bounds follow the existing semantics: fromdate inclusive, todate exclusive.
     * - Requires indexes: invoices(invoicedate), invoiceitems(invoiceuuid, consultantuuid), userstatus(useruuid, statusdate).
     *
     * @param fromdate Start date (inclusive) for invoice date range
     * @param todate End date (exclusive) for invoice date range
     * @return List of invoices with cross-company consultants
     */
    @SuppressWarnings("unchecked")
    public List<Invoice> findCrossCompanyInvoicesByDateRange(LocalDate fromdate, LocalDate todate) {
        LocalDate finalFromdate = fromdate != null ? fromdate : LocalDate.of(2014, 1, 1);
        LocalDate finalTodate = todate != null ? todate : LocalDate.now();

        String sql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :fromdate
                  AND invoicedate <  :todate
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            )
            SELECT DISTINCT i.*
            FROM inv i
            JOIN status_pick sp 
              ON sp.invoiceuuid = i.uuid
            WHERE sp.rn = 1
              AND i.companyuuid IS NOT NULL
              AND sp.users_companyuuid IS NOT NULL
              AND sp.users_companyuuid <> i.companyuuid
            ORDER BY i.invoicedate DESC, i.invoicenumber DESC
        """;

        return em.createNativeQuery(sql, Invoice.class)
                .setParameter("fromdate", finalFromdate)
                .setParameter("todate", finalTodate)
                .getResultList();
    }



    /**
     * Returns pairs of client invoices that contain cross-company consultant lines and an optional
     * referring internal invoice (if present).
     *
     * Algorithm (MariaDB 11 optimized):
     * - Phase 1 (SQL):
     *   * Select client invoices within date range (CREATED, type INVOICE) that have at least one cross-company
     *     consultant, determined by picking for each (invoice, consultant) the latest userstatus where statusdate
     *     <= invoicedate (window function ROW_NUMBER()).
     *   * For each such client invoice, pick at most one internal invoice (status in QUEUED or CREATED) referring via
     *     invoice_ref.
     * - Phase 2 (SQL):
     *   * For all involved invoices (client + internal), fetch lines and compute a per-line cross-company flag using
     *     the same "as-of" userstatus logic.
     * - Phase 3 (Java):
     *   * Assemble DTOs and compute totals (sum of hours*rate) before VAT.
     *
     * Date bounds: from inclusive, to exclusive.
     *
     * Note: Lines with consultantuuid = NULL (e.g., calculated fees/discounts) are marked crossCompany = false.
     *
     * @param fromdate Start date (inclusive)
     * @param todate End date (exclusive)
     * @return List of client/internal invoice pairs. Internal may be null when not present.
     */
    public java.util.List<CrossCompanyInvoicePairDTO> findCrossCompanyInvoicesWithInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String pickSql = """
        WITH inv AS (
            SELECT *
            FROM invoices
            WHERE invoicedate >= :from
              AND invoicedate <  :to
              AND status = 'CREATED'
              AND type   = 'INVOICE'
        ),
        status_pick AS (
            SELECT 
                i.uuid AS invoiceuuid,
                i.companyuuid AS invoice_companyuuid,
                ii.consultantuuid,
                us.companyuuid AS users_companyuuid,
                ROW_NUMBER() OVER (
                    PARTITION BY i.uuid, ii.consultantuuid
                    ORDER BY us.statusdate DESC
                ) AS rn
            FROM inv i
            JOIN invoiceitems ii 
              ON ii.invoiceuuid = i.uuid
             AND ii.consultantuuid IS NOT NULL
            JOIN userstatus us 
              ON us.useruuid = ii.consultantuuid
             AND us.statusdate <= i.invoicedate
        ),
        cross_clients AS (
            SELECT DISTINCT i.*
            FROM inv i
            JOIN status_pick sp ON sp.invoiceuuid = i.uuid
            WHERE sp.rn = 1
              AND i.companyuuid IS NOT NULL
              AND sp.users_companyuuid IS NOT NULL
              AND sp.users_companyuuid <> i.companyuuid
        ),
        internal_pick AS (
            SELECT 
                ci.uuid AS client_uuid,
                ii.uuid AS internal_uuid,
                ROW_NUMBER() OVER (
                   PARTITION BY ci.uuid
                   ORDER BY ii.invoicedate DESC, ii.invoicenumber DESC
                ) AS r
            FROM cross_clients ci
            JOIN invoices ii
              ON ii.invoice_ref_uuid = ci.uuid
             AND ii.type          = 'INTERNAL'
             AND ii.status IN ('QUEUED','CREATED')
        )
        SELECT 
            ci.uuid       AS client_uuid,
            COALESCE(ip.internal_uuid, NULL) AS internal_uuid
        FROM cross_clients ci
        LEFT JOIN internal_pick ip
          ON ip.client_uuid = ci.uuid AND ip.r = 1
        ORDER BY ci.invoicedate DESC, ci.invoicenumber DESC
    """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> pairs = em.createNativeQuery(pickSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (pairs.isEmpty()) return java.util.List.of();

        java.util.LinkedHashSet<String> allInvoiceUuids = new java.util.LinkedHashSet<>();
        for (Object[] r : pairs) {
            String clientUuid = (String) r[0];
            String internalUuid = (String) r[1];
            allInvoiceUuids.add(clientUuid);
            if (internalUuid != null) allInvoiceUuids.add(internalUuid);
        }

        java.util.List<Invoice> allInvoices = allInvoiceUuids.isEmpty() ? java.util.List.of()
                : Invoice.list("uuid in ?1", allInvoiceUuids);
        java.util.Map<String, Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(Invoice::getUuid, i -> i));

        // Build dynamic IN clause for native SQL
        StringBuilder ph = new StringBuilder();
        int size = allInvoiceUuids.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) ph.append(',');
            ph.append('?');
        }

        String linesSql = """
        WITH base AS (
            SELECT 
                i.uuid AS invoiceuuid,
                i.companyuuid AS invoice_companyuuid,
                i.invoicedate,
                ii.uuid AS line_uuid,
                ii.itemname,
                ii.description,
                ii.hours,
                ii.rate,
                ii.consultantuuid
            FROM invoices i
            JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
            WHERE i.uuid IN (%s)
        ),
        status_pick AS (
            SELECT 
                b.invoiceuuid,
                b.consultantuuid,
                us.companyuuid AS users_companyuuid,
                ROW_NUMBER() OVER (
                    PARTITION BY b.invoiceuuid, b.consultantuuid
                    ORDER BY us.statusdate DESC
                ) AS rn
            FROM base b
            LEFT JOIN userstatus us
              ON us.useruuid = b.consultantuuid
             AND us.statusdate <= b.invoicedate
        )
        SELECT 
            b.invoiceuuid,
            b.line_uuid,
            b.itemname,
            b.description,
            b.hours,
            b.rate,
            b.consultantuuid,
            CASE 
              WHEN b.consultantuuid IS NULL THEN 0
              WHEN sp.rn = 1 
                   AND sp.users_companyuuid IS NOT NULL
                   AND sp.users_companyuuid <> b.invoice_companyuuid 
                THEN 1
              ELSE 0
            END AS cross_company
        FROM base b
        LEFT JOIN status_pick sp
          ON sp.invoiceuuid = b.invoiceuuid
         AND sp.consultantuuid = b.consultantuuid
         AND sp.rn = 1
        ORDER BY b.invoiceuuid
    """.formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1;
        for (String id : allInvoiceUuids) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new InvoiceLineDTO(lineId, name, desc, hours, rate, round2(amount), cuuid, cross));
        }

        java.util.List<CrossCompanyInvoicePairDTO> result = new java.util.ArrayList<>();
        for (Object[] r : pairs) {
            String clientUuid = (String) r[0];
            String internalUuid = (String) r[1];

            Invoice client = invoiceById.get(clientUuid);
            if (client == null) continue;
            java.util.List<InvoiceLineDTO> clientLines = linesByInvoice.getOrDefault(clientUuid, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(InvoiceLineDTO::amountNoTax).sum();
            String resolvedClientName = resolveClientName(client);
            SimpleInvoiceDTO clientDto = new SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines,
                    client.getControlStatus(),
                    client.getControlNote(),
                    client.getControlStatusUpdatedAt(),
                    client.getControlStatusUpdatedBy()
            );

            SimpleInvoiceDTO internalDto = null;
            if (internalUuid != null) {
                Invoice internal = invoiceById.get(internalUuid);
                if (internal != null) {
                    java.util.List<InvoiceLineDTO> ilines = linesByInvoice.getOrDefault(internalUuid, java.util.List.of());
                    double itotal = ilines.stream().mapToDouble(InvoiceLineDTO::amountNoTax).sum();
                    internalDto = new SimpleInvoiceDTO(
                            internal.getUuid(),
                            internal.getInvoicenumber(),
                            internal.getInvoicedate(),
                            internal.getCompany() != null ? internal.getCompany().getUuid() : null,
                            internal.getCompany() != null ? internal.getCompany().getName() : null,
                            null,
                            internal.getStatus(),
                            internal.getEconomicsStatus(),
                            round2(itotal),
                            ilines,
                            null,
                            null,
                            null,
                            null
                    );
                }
            }

            result.add(new CrossCompanyInvoicePairDTO(clientDto, internalDto));
        }

        return result;
    }

    /**
     * Finds client/internal invoice pairs where the client invoice contains cross-company
     * consultant lines and the sum of client invoice lines (hours * rate) is strictly less
     * than the sum of the corresponding internal invoice lines. The internal invoice is
     * linked by invoice_ref = client.invoicenumber and must be in status QUEUED or CREATED.
     *
     * Implementation details (MariaDB 11):
     * - Uses window functions (ROW_NUMBER) to select the latest userstatus per (invoice, consultant)
     *   on or before the invoice date to determine cross-company involvement.
     * - Selects at most one internal invoice per client (latest by invoicedate then invoicenumber).
     * - Computes totals for client and internal in SQL and filters to client_total < internal_total.
     * - Fetches lines for both invoices in one SQL and computes per-line crossCompany flags.
     *
     * Date window semantics: from inclusive, to exclusive.
     *
     * Currency note: Amounts are compared in native invoice currency without FX conversion.
     * Ensure client/internal use the same currency for meaningful comparison.
     *
     * @param fromdate start date (inclusive)
     * @param todate end date (exclusive)
     * @return list of pairs containing the client and the selected internal invoice
     */
    public java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO>
    findCrossCompanyClientLessThanInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String pairSql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :from
                  AND invoicedate <  :to
                  AND status = 'CREATED'
                  AND type   = 'INVOICE'
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            ),
            cross_clients AS (
                SELECT DISTINCT i.*
                FROM inv i
                JOIN status_pick sp ON sp.invoiceuuid = i.uuid
                WHERE sp.rn = 1
                  AND i.companyuuid IS NOT NULL
                  AND sp.users_companyuuid IS NOT NULL
                  AND sp.users_companyuuid <> i.companyuuid
            ),
            internal_pick AS (
                SELECT 
                    ci.uuid AS client_uuid,
                    ii.uuid AS internal_uuid,
                    ROW_NUMBER() OVER (
                       PARTITION BY ci.uuid
                       ORDER BY ii.invoicedate DESC, ii.invoicenumber DESC
                    ) AS r
                FROM cross_clients ci
                JOIN invoices ii
                  ON ii.invoice_ref_uuid = ci.uuid
                 AND ii.type          = 'INTERNAL'
                 AND ii.status IN ('QUEUED','CREATED')
            ),
            chosen AS (
                SELECT client_uuid, internal_uuid
                FROM internal_pick
                WHERE r = 1
            ),
            client_totals AS (
                SELECT i.uuid AS invoiceuuid, COALESCE(SUM(ii.hours * ii.rate), 0) AS total
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (SELECT client_uuid FROM chosen)
                GROUP BY i.uuid
            ),
            internal_totals AS (
                SELECT i.uuid AS invoiceuuid, COALESCE(SUM(ii.hours * ii.rate), 0) AS total
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (SELECT internal_uuid FROM chosen)
                GROUP BY i.uuid
            )
            SELECT c.client_uuid, c.internal_uuid
            FROM chosen c
            JOIN client_totals  ct ON ct.invoiceuuid  = c.client_uuid
            JOIN internal_totals it ON it.invoiceuuid = c.internal_uuid
            WHERE ct.total < it.total
            ORDER BY c.client_uuid
        """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> pairs = em.createNativeQuery(pairSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (pairs.isEmpty()) return java.util.List.of();

        // Collect invoice IDs (client + internal)
        java.util.LinkedHashSet<String> allInvoiceUuids = new java.util.LinkedHashSet<>();
        for (Object[] r : pairs) {
            allInvoiceUuids.add((String) r[0]); // client
            allInvoiceUuids.add((String) r[1]); // internal
        }

        // Load headers
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> allInvoices =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", allInvoiceUuids);
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Build dynamic placeholders for IN clause
        StringBuilder ph = new StringBuilder();
        int size = allInvoiceUuids.size();
        for (int i = 0; i < size; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Lines SQL with per-line cross-company flag via userstatus as-of invoicedate
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : allInvoiceUuids) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        // Group lines per invoice
        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineId, name, desc, hours, rate, round2(amount), cuuid, cross
                    ));
        }

        // Assemble DTO pairs
        java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO> result = new java.util.ArrayList<>();
        for (Object[] r : pairs) {
            String clientUuid = (String) r[0];
            String internalUuid = (String) r[1];

            var client = invoiceById.get(clientUuid);
            var internal = invoiceById.get(internalUuid);
            if (client == null || internal == null) continue;

            var clientLines = linesByInvoice.getOrDefault(clientUuid, java.util.List.of());
            var internalLines = linesByInvoice.getOrDefault(internalUuid, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
            double internalTotal = internalLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();

            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines,
                    client.getControlStatus(),
                    client.getControlNote(),
                    client.getControlStatusUpdatedAt(),
                    client.getControlStatusUpdatedBy()
            );

            var internalDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    internal.getUuid(),
                    internal.getInvoicenumber(),
                    internal.getInvoicedate(),
                    internal.getCompany() != null ? internal.getCompany().getUuid() : null,
                    internal.getCompany() != null ? internal.getCompany().getName() : null,
                    null,
                    internal.getStatus(),
                    internal.getEconomicsStatus(),
                    round2(internalTotal),
                    internalLines,
                    null,
                    null,
                    null,
                    null
            );

            result.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO(clientDto, internalDto));
        }

        return result;
    }

    /**
     * Finds client invoices (status CREATED, type INVOICE) that contain at least one cross-company
     * consultant line, but have no referring INTERNAL invoice in status QUEUED or CREATED.
     * Cross-company is determined by evaluating each consultant's latest userstatus on or before
     * the invoice date (as-of) and comparing that company UUID to the issuing invoice's company UUID.
     *
     * Implementation (MariaDB 11):
     * - Window function (ROW_NUMBER) to pick latest userstatus per (invoice, consultant).
     * - Selects client invoices with cross-company involvement, then left-joins to INTERNAL invoices
     *   (invoice_ref = client.invoicenumber, status in QUEUED/CREATED) and filters those with none.
     * - Fetches invoice lines and computes a per-line crossCompany boolean.
     *
     * Date window semantics: from inclusive, to exclusive.
     *
     * @param fromdate Start date (inclusive)
     * @param todate   End date (exclusive)
     * @return List of SimpleInvoiceDTO for client invoices without a referring internal invoice
     */
    public java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO>
    findCrossCompanyClientInvoicesWithoutInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String selectSql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :from
                  AND invoicedate <  :to
                  AND status = 'CREATED'
                  AND type   = 'INVOICE'
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            ),
            cross_clients AS (
                SELECT DISTINCT i.*
                FROM inv i
                JOIN status_pick sp ON sp.invoiceuuid = i.uuid
                WHERE sp.rn = 1
                  AND i.companyuuid IS NOT NULL
                  AND sp.users_companyuuid IS NOT NULL
                  AND sp.users_companyuuid <> i.companyuuid
            )
            SELECT ci.uuid AS client_uuid
            FROM cross_clients ci
            LEFT JOIN invoices ii
              ON ii.invoice_ref_uuid = ci.uuid
             AND ii.type = 'INTERNAL'
             AND ii.status IN ('QUEUED','CREATED')
            WHERE ii.uuid IS NULL
            ORDER BY ci.invoicedate DESC, ci.invoicenumber DESC
        """;

        @SuppressWarnings("unchecked")
        java.util.List<String> clientIds = em.createNativeQuery(selectSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (clientIds.isEmpty()) return java.util.List.of();

        // Load invoice headers for selected client invoices
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> clients =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", new java.util.LinkedHashSet<>(clientIds));
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = clients.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Prepare dynamic IN placeholders for native SQL
        StringBuilder ph = new StringBuilder();
        int size = clientIds.size();
        for (int i = 0; i < size; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Fetch lines and compute per-line crossCompany using userstatus as-of invoicedate
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : clientIds) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineId, name, desc, hours, rate, round2(amount), cuuid, cross
                    ));
        }

        java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO> result = new java.util.ArrayList<>();
        for (String clientUuid : clientIds) {
            var client = invoiceById.get(clientUuid);
            if (client == null) continue;
            var clientLines = linesByInvoice.getOrDefault(clientUuid, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();

            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines,
                    client.getControlStatus(),
                    client.getControlNote(),
                    client.getControlStatusUpdatedAt(),
                    client.getControlStatusUpdatedBy()
            );
            result.add(clientDto);
        }

        return result;
    }

    /**
     * Finds client/internal invoice pairs where the client invoice is a regular client invoice (type INVOICE)
     * that currently has status CREDIT_NOTE and contains at least one cross-company consultant line, and where
     * there exists a referring INTERNAL invoice (status in QUEUED/CREATED) linked via invoice_ref.
     *
     * Cross-company evaluation uses the consultant's latest userstatus on or before the invoice date (as-of)
     * to compare the consultant company to the issuing invoice company. Implemented using MariaDB 11 window
     * functions for performance.
     *
     * Date semantics: from inclusive, to exclusive.
     *
     * @param fromdate Start date (inclusive)
     * @param todate   End date (exclusive)
     * @return List of pairs (client + internal) with header data and line-level crossCompany flags
     */
    public List<CrossCompanyInvoicePairDTO>
    findCrossCompanyClientInvoicesStatusCreditNoteWithInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String pairsSql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :from
                  AND invoicedate <  :to
                  # TODO: Find a way to sort invoices that have a credit note away
                  AND type   = 'INVOICE'
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            ),
            cross_clients AS (
                SELECT DISTINCT i.*
                FROM inv i
                JOIN status_pick sp ON sp.invoiceuuid = i.uuid
                WHERE sp.rn = 1
                  AND i.companyuuid IS NOT NULL
                  AND sp.users_companyuuid IS NOT NULL
                  AND sp.users_companyuuid <> i.companyuuid
            ),
            internal_pick AS (
                SELECT 
                    ci.uuid AS client_uuid,
                    ii.uuid AS internal_uuid,
                    ROW_NUMBER() OVER (
                       PARTITION BY ci.uuid
                       ORDER BY ii.invoicedate DESC, ii.invoicenumber DESC
                    ) AS r
                FROM cross_clients ci
                JOIN invoices ii
                  ON ii.invoice_ref_uuid = ci.uuid
                 AND ii.type          = 'INTERNAL'
                 AND ii.status IN ('QUEUED','CREATED')
            )
            SELECT ip.client_uuid, ip.internal_uuid
            FROM internal_pick ip
            WHERE ip.r = 1
            ORDER BY ip.client_uuid
        """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> pairs = em.createNativeQuery(pairsSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (pairs.isEmpty()) return java.util.List.of();

        // Collect client+internal invoice UUIDs
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        for (Object[] r : pairs) { allIds.add((String) r[0]); allIds.add((String) r[1]); }

        // Load headers
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> allInvoices =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", allIds);
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Build dynamic IN placeholders
        StringBuilder ph = new StringBuilder();
        int n = allIds.size();
        for (int i = 0; i < n; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Lines and per-line cross-company flag
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : allIds) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invoiceUuid = (String) r[0];
            String lineUuid    = (String) r[1];
            String itemName    = (String) r[2];
            String description = (String) r[3];
            Double hours       = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate        = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String consultUuid = (String) r[6];
            boolean cross      = ((Number) r[7]).intValue() == 1;
            double amount      = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invoiceUuid, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineUuid, itemName, description, hours, rate,
                            round2(amount), consultUuid, cross
                    ));
        }

        java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO> result = new java.util.ArrayList<>();
        for (Object[] r : pairs) {
            String clientId = (String) r[0];
            String internalId = (String) r[1];
            var client = invoiceById.get(clientId);
            var internal = invoiceById.get(internalId);
            if (client == null || internal == null) continue;

            var clientLines = linesByInvoice.getOrDefault(clientId, java.util.List.of());
            var internalLines = linesByInvoice.getOrDefault(internalId, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
            double internalTotal = internalLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();

            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines,
                    client.getControlStatus(),
                    client.getControlNote(),
                    client.getControlStatusUpdatedAt(),
                    client.getControlStatusUpdatedBy()
            );

            var internalDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    internal.getUuid(),
                    internal.getInvoicenumber(),
                    internal.getInvoicedate(),
                    internal.getCompany() != null ? internal.getCompany().getUuid() : null,
                    internal.getCompany() != null ? internal.getCompany().getName() : null,
                    null,
                    internal.getStatus(),
                    internal.getEconomicsStatus(),
                    round2(internalTotal),
                    internalLines,
                    null,
                    null,
                    null,
                    null
            );

            result.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO(clientDto, internalDto));
        }

        return result;
    }

    /**
     * Returns client invoices (type INVOICE) that have more than one INTERNAL invoice (status in
     * QUEUED or CREATED) referencing them via invoice_ref, within the given date window.
     *
     * Date semantics: from inclusive, to exclusive; applies to the client's invoicedate.
     *
     * Implementation outline (MariaDB 11):
     * - CTE clients: select client invoices by date and type INVOICE.
     * - CTE internals: all INTERNAL invoices in status QUEUED/CREATED.
     * - CTE pairs: join by invoice_ref to associate internals to clients.
     * - CTE dup_clients: retain only clients with COUNT(internal) > 1.
     * - Fetch headers for all involved invoices, then lines with per-line crossCompany flags using
     *   userstatus as-of invoicedate via ROW_NUMBER window function.
     *
     * @param fromdate Start date (inclusive), format yyyy-MM-dd
     * @param todate   End date (exclusive), format yyyy-MM-dd
     * @return List of ClientWithInternalsDTO containing the client and all its internal invoices
     */
    public java.util.List<ClientWithInternalsDTO> findClientInvoicesWithMultipleInternals(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String selectSql = """
            WITH clients AS (
                SELECT *
                FROM invoices i
                WHERE i.invoicedate >= :from
                  AND i.invoicedate <  :to
                  AND i.type = 'INVOICE'
                  AND i.status <> 'DRAFT'
            ),
            internals AS (
                SELECT ii.*
                FROM invoices ii
                WHERE ii.type = 'INTERNAL'
                  AND ii.status IN ('QUEUED','CREATED')
            ),
            pairs AS (
                SELECT c.uuid AS client_uuid, i.uuid AS internal_uuid
                FROM clients c
                JOIN internals i ON i.invoice_ref_uuid = c.uuid
            ),
            dup_clients AS (
                SELECT client_uuid, COUNT(*) AS internal_count
                FROM pairs
                GROUP BY client_uuid
                HAVING COUNT(*) > 1
            )
            SELECT p.client_uuid, p.internal_uuid
            FROM pairs p
            JOIN dup_clients d ON d.client_uuid = p.client_uuid
            ORDER BY p.client_uuid
        """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = em.createNativeQuery(selectSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (rows.isEmpty()) return java.util.List.of();

        // Map client -> list of internal UUIDs and collect all IDs
        java.util.LinkedHashMap<String, java.util.List<String>> clientToInternals = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        for (Object[] r : rows) {
            String clientUuid = (String) r[0];
            String internalUuid = (String) r[1];
            clientToInternals.computeIfAbsent(clientUuid, k -> new java.util.ArrayList<>()).add(internalUuid);
            allIds.add(clientUuid);
            allIds.add(internalUuid);
        }

        // Load invoice headers
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> allInvoices =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", allIds);
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Build placeholders for IN clause
        StringBuilder ph = new StringBuilder();
        int n = allIds.size();
        for (int i = 0; i < n; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Fetch lines and compute per-line crossCompany (userstatus as-of invoicedate)
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : allIds) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineId, name, desc, hours, rate, round2(amount), cuuid, cross
                    ));
        }

        // Assemble DTOs
        java.util.List<ClientWithInternalsDTO> result = new java.util.ArrayList<>();
        for (var e : clientToInternals.entrySet()) {
            String clientId = e.getKey();
            java.util.List<String> internalIds = e.getValue();

            var client = invoiceById.get(clientId);
            if (client == null) continue;
            var clientLines = linesByInvoice.getOrDefault(clientId, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines,
                    client.getControlStatus(),
                    client.getControlNote(),
                    client.getControlStatusUpdatedAt(),
                    client.getControlStatusUpdatedBy()
            );

            java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO> internals = new java.util.ArrayList<>();
            for (String internalId : internalIds) {
                var inv = invoiceById.get(internalId);
                if (inv == null) continue;
                var lines = linesByInvoice.getOrDefault(internalId, java.util.List.of());
                double total = lines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
                internals.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                        inv.getUuid(),
                        inv.getInvoicenumber(),
                        inv.getInvoicedate(),
                        inv.getCompany() != null ? inv.getCompany().getUuid() : null,
                        inv.getCompany() != null ? inv.getCompany().getName() : null,
                        null,
                        inv.getStatus(),
                        inv.getEconomicsStatus(),
                        round2(total),
                        lines,
                        null,
                        null,
                        null,
                        null
                ));
            }

            result.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.ClientWithInternalsDTO(
                    clientDto, internals, internals.size()
            ));
        }

        return result;
    }

    /**
     * Resolves the client name for a given invoice by following the reference chain
     * Invoice.contractuuid -> Contract.clientuuid -> Client.name.
     * If the contract or client cannot be found (or names are blank), the method
     * safely falls back to the snapshot name stored on the invoice itself.
     *
     * Note: This method is used when building SimpleInvoiceDTO for client invoices
     * to ensure the freshest canonical client name is returned.
     *
     * @param invoice the invoice whose client name should be resolved
     * @return the client name from Client entity, or invoice.clientname as fallback
     */
    private String resolveClientName(Invoice invoice) {
        if (invoice == null) return null;
        try {
            String contractUuid = invoice.getContractuuid();
            if (contractUuid != null && !contractUuid.isBlank()) {
                Contract contract = Contract.findById(contractUuid);
                if (contract != null) {
                    String clientUuid = contract.getClientuuid();
                    if (clientUuid != null && !clientUuid.isBlank()) {
                        Client client = Client.findById(clientUuid);
                        if (client != null && client.getName() != null && !client.getName().isBlank()) {
                            return client.getName();
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // Fall back to invoice snapshot if anything fails
        }
        return invoice.getClientname();
    }
}

package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLedgerDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLedgerListResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Application service for the accounting ledger view.
 * Provides a paginated, filterable, sortable query that joins invoices
 * with contracts, clients (for account manager), and upload records (for error status).
 */
@JBossLog
@ApplicationScoped
public class InvoiceLedgerService {

    @Inject
    EntityManager em;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "invoicenumber", "type", "companyname", "clientname", "projectname",
            "invoicedate", "duedate", "currency", "sumnotax", "sumwithtax",
            "status", "economicsstatus"
    );

    private static final Map<String, String> SORT_FIELD_MAP = Map.ofEntries(
            Map.entry("invoicenumber", "i.invoicenumber"),
            Map.entry("type", "i.type"),
            Map.entry("companyname", "co.name"),
            Map.entry("clientname", "i.clientname"),
            Map.entry("projectname", "i.projectname"),
            Map.entry("invoicedate", "i.invoicedate"),
            Map.entry("duedate", "i.duedate"),
            Map.entry("currency", "i.currency"),
            Map.entry("sumnotax", "sum_no_tax"),
            Map.entry("sumwithtax", "sum_with_tax"),
            Map.entry("status", "i.status"),
            Map.entry("economicsstatus", "i.economics_status")
    );

    /**
     * Queries the ledger with all filters, sorting, and pagination.
     *
     * @param type            invoice type filter (e.g. "INVOICE", "CREDIT_NOTE")
     * @param status          invoice status filter (e.g. "CREATED", "DRAFT")
     * @param economicsStatus economics status filter (e.g. "BOOKED", "PAID")
     * @param companyuuid     issuing company UUID filter
     * @param search          free-text search on invoice number, clientname, projectname
     * @param fromDate        invoice date range start (inclusive)
     * @param toDate          invoice date range end (exclusive)
     * @param currency        currency filter (e.g. "DKK", "EUR")
     * @param sortBy          sort field name
     * @param sortOrder       "asc" or "desc"
     * @param page            zero-based page index
     * @param size            page size
     * @return paginated ledger response
     */
    @SuppressWarnings("unchecked")
    public InvoiceLedgerListResponse findLedger(
            String type,
            String status,
            String economicsStatus,
            String companyuuid,
            String search,
            LocalDate fromDate,
            LocalDate toDate,
            String currency,
            String sortBy,
            String sortOrder,
            int page,
            int size) {

        var whereClause = new StringBuilder();
        var params = new ArrayList<QueryParam>();
        int paramIdx = 1;

        // --- dynamic WHERE filters ---

        if (type != null && !type.isBlank()) {
            whereClause.append(" AND i.type = ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, type));
        }

        if (status != null && !status.isBlank()) {
            whereClause.append(" AND i.status = ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, status));
        }

        if (economicsStatus != null && !economicsStatus.isBlank()) {
            whereClause.append(" AND i.economics_status = ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, economicsStatus));
        }

        if (companyuuid != null && !companyuuid.isBlank()) {
            whereClause.append(" AND i.companyuuid = ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, companyuuid));
        }

        if (currency != null && !currency.isBlank()) {
            whereClause.append(" AND i.currency = ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, currency));
        }

        if (fromDate != null) {
            whereClause.append(" AND i.invoicedate >= ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, fromDate));
        }

        if (toDate != null) {
            whereClause.append(" AND i.invoicedate < ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, toDate));
        }

        if (search != null && !search.isBlank()) {
            String searchPattern = "%" + search.trim() + "%";
            whereClause.append(" AND (CAST(i.invoicenumber AS CHAR) LIKE ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, searchPattern));
            whereClause.append(" OR i.clientname LIKE ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, searchPattern));
            whereClause.append(" OR i.projectname LIKE ?").append(paramIdx);
            params.add(new QueryParam(paramIdx++, searchPattern));
            whereClause.append(")");
        }

        // --- ORDER BY ---
        String orderColumn = "i.invoicedate";
        String orderDir = "DESC";

        if (sortBy != null && !sortBy.isBlank()) {
            String normalizedSort = sortBy.trim().toLowerCase(Locale.ROOT);
            if (ALLOWED_SORT_FIELDS.contains(normalizedSort)) {
                orderColumn = SORT_FIELD_MAP.get(normalizedSort);
            }
        }
        if (sortOrder != null && sortOrder.trim().equalsIgnoreCase("asc")) {
            orderDir = "ASC";
        }

        // --- Build the full SQL ---
        // The query uses subselects for sums to avoid GROUP BY complications with the main row data.
        // LEFT JOIN on contracts and clients for account manager resolution.
        // LEFT JOIN on uploads subquery for error status.
        String baseSql = """
                SELECT
                    i.uuid,
                    i.invoicenumber,
                    i.type,
                    cl.accountmanager AS account_manager_uuid,
                    CONCAT(COALESCE(u.firstname, ''), ' ', COALESCE(u.lastname, '')) AS account_manager_name,
                    co.uuid AS company_uuid,
                    co.name AS company_name,
                    i.clientname,
                    i.projectname,
                    i.invoicedate,
                    i.duedate,
                    i.currency,
                    COALESCE(item_sums.sum_no_tax, 0) AS sum_no_tax,
                    COALESCE(item_sums.sum_no_tax, 0) * (1 + i.vat / 100) AS sum_with_tax,
                    i.status,
                    i.economics_status,
                    CASE WHEN upl.has_error = 1 THEN TRUE ELSE FALSE END AS has_upload_error,
                    upl.last_error AS last_upload_error
                FROM invoices i
                LEFT JOIN companies co ON co.uuid = i.companyuuid
                LEFT JOIN contracts ct ON ct.uuid = i.contractuuid
                LEFT JOIN client cl ON cl.uuid = ct.clientuuid
                LEFT JOIN user u ON u.uuid = cl.accountmanager
                LEFT JOIN (
                    SELECT invoiceuuid, SUM(rate * hours) AS sum_no_tax
                    FROM invoiceitems
                    GROUP BY invoiceuuid
                ) item_sums ON item_sums.invoiceuuid = i.uuid
                LEFT JOIN (
                    SELECT
                        invoiceuuid,
                        MAX(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS has_error,
                        SUBSTRING_INDEX(GROUP_CONCAT(
                            CASE WHEN status = 'FAILED' THEN last_error ELSE NULL END
                            ORDER BY updated_at DESC SEPARATOR '|||'
                        ), '|||', 1) AS last_error
                    FROM invoice_economics_uploads
                    GROUP BY invoiceuuid
                ) upl ON upl.invoiceuuid = i.uuid
                WHERE 1=1
                """;

        String dataSql = baseSql + whereClause + " ORDER BY " + orderColumn + " " + orderDir
                + " LIMIT " + size + " OFFSET " + ((long) page * size);

        String countSql = "SELECT COUNT(*) FROM (" + baseSql + whereClause + ") AS cnt";

        // --- Execute data query ---
        Query dataQuery = em.createNativeQuery(dataSql);
        for (QueryParam p : params) {
            dataQuery.setParameter(p.index, p.value);
        }

        List<Object[]> rows = dataQuery.getResultList();
        List<InvoiceLedgerDTO> dtos = rows.stream().map(this::mapRow).toList();

        // --- Execute count query ---
        Query countQuery = em.createNativeQuery(countSql);
        for (QueryParam p : params) {
            countQuery.setParameter(p.index, p.value);
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new InvoiceLedgerListResponse(dtos, total, page, size);
    }

    private InvoiceLedgerDTO mapRow(Object[] row) {
        return new InvoiceLedgerDTO(
                asString(row[0]),                                           // uuid
                asInt(row[1]),                                              // invoicenumber
                asString(row[2]),                                           // type
                asString(row[3]),                                           // accountManagerUuid
                trimName(asString(row[4])),                                 // accountManagerName
                asString(row[5]),                                           // companyUuid
                asString(row[6]),                                           // companyName
                asString(row[7]),                                           // clientname
                asString(row[8]),                                           // projectname
                asLocalDate(row[9]),                                        // invoicedate
                asLocalDate(row[10]),                                       // duedate
                asString(row[11]),                                          // currency
                asDouble(row[12]),                                          // sumNoTax
                asDouble(row[13]),                                          // sumWithTax
                asString(row[14]),                                          // status
                asString(row[15]),                                          // economicsStatus
                row[16] != null && asBool(row[16]),                         // hasUploadError
                asString(row[17])                                           // lastUploadError
        );
    }

    // --- type-safe extraction helpers ---

    private static String asString(Object val) {
        return val != null ? val.toString() : null;
    }

    private static int asInt(Object val) {
        return val != null ? ((Number) val).intValue() : 0;
    }

    private static double asDouble(Object val) {
        return val != null ? ((Number) val).doubleValue() : 0.0;
    }

    private static boolean asBool(Object val) {
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private static LocalDate asLocalDate(Object val) {
        if (val instanceof LocalDate ld) return ld;
        if (val instanceof Date d) return d.toLocalDate();
        return null;
    }

    /**
     * Trims the concatenated name and returns null if effectively empty.
     */
    private static String trimName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Internal helper for tracking positional query parameters.
     */
    private record QueryParam(int index, Object value) {}
}

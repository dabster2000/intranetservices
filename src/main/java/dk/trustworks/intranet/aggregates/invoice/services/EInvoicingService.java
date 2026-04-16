package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.resources.dto.EInvoicingListItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only service for the E-Invoicing listing.
 * Returns booked invoices sent via EAN within a given date range.
 *
 * SPEC-INV-001 S8.10.
 */
@ApplicationScoped
public class EInvoicingService {

    @Inject
    EntityManager em;

    /**
     * Lists invoices that were sent via EAN ({@code send_by = 'ean'}) within
     * the given date range, ordered by invoice date descending.
     *
     * @param from start of date range (inclusive)
     * @param to   end of date range (inclusive)
     * @return projection list; never null
     */
    public List<EInvoicingListItem> listEanInvoices(LocalDate from, LocalDate to) {
        String sql = """
                SELECT i.uuid,
                       i.invoicenumber,
                       c.name              AS billing_client_name,
                       c.ean               AS billing_client_ean,
                       i.invoicedate,
                       COALESCE(SUM(ii.hours * ii.rate), 0) AS sum_no_tax,
                       i.vat,
                       i.economics_status
                FROM invoices i
                LEFT JOIN client c ON c.uuid = i.billing_client_uuid
                LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.send_by = 'ean'
                  AND i.invoicedate >= :from
                  AND i.invoicedate <= :to
                GROUP BY i.uuid, i.invoicenumber, c.name, c.ean,
                         i.invoicedate, i.vat, i.economics_status
                ORDER BY i.invoicedate DESC
                """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("from", from);
        query.setParameter("to", to);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    private EInvoicingListItem mapRow(Object[] row) {
        return new EInvoicingListItem(
                asString(row[0]),     // invoiceUuid
                asInt(row[1]),        // invoicenumber
                asString(row[2]),     // billingClientName
                asString(row[3]),     // billingClientEan
                asLocalDate(row[4]),  // invoicedate
                asDouble(row[5]),     // sumNoTax
                asDouble(row[6]),     // vat
                asString(row[7])      // economicsStatus
        );
    }

    private static String asString(Object val) {
        return val != null ? val.toString() : null;
    }

    private static int asInt(Object val) {
        return val != null ? ((Number) val).intValue() : 0;
    }

    private static double asDouble(Object val) {
        return val != null ? ((Number) val).doubleValue() : 0.0;
    }

    private static LocalDate asLocalDate(Object val) {
        if (val instanceof LocalDate ld) return ld;
        if (val instanceof Date d) return d.toLocalDate();
        return null;
    }
}

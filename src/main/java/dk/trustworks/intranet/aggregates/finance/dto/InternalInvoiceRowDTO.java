package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single intercompany (INTERNAL) invoice cost record for the EBITDA source data export.
 *
 * <p>Data originates from two mutually exclusive sources depending on the invoice lifecycle:
 * <ul>
 *   <li>{@code QUEUED_INVOICE} — invoice is agreed but not yet uploaded to e-conomic;
 *       cost is computed from {@code invoices} + {@code invoiceitems} tables.</li>
 *   <li>{@code CREATED_GL} — invoice has been uploaded to e-conomic; cost is authoritative
 *       from {@code finance_details} GL accounts 3050/3055/3070/3075/1350.</li>
 * </ul>
 *
 * Used as one row in the "Internal Invoices" Excel tab of the EBITDA export.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalInvoiceRowDTO {

    /** UUID of the invoice (from invoices table; null for CREATED_GL rows that have no single invoice UUID) */
    private String invoiceUuid;

    /** Sequential invoice number (from invoices table; 0 for CREATED_GL aggregate rows) */
    private int invoiceNumber;

    /** Invoice date in ISO format (YYYY-MM-DD); for CREATED_GL rows this is the first day of the GL month */
    private String invoiceDate;

    /** Month key in YYYYMM format (e.g., "202407") */
    private String monthKey;

    /** UUID of the Trustworks entity that issued the invoice (companyuuid on the invoice) */
    private String issuerCompanyUuid;

    /** UUID of the Trustworks entity that is the debtor (debtor_companyuuid on the invoice / companyuuid in GL) */
    private String debtorCompanyUuid;

    /**
     * UUID of the reference invoice that this internal invoice relates to.
     * Populated from {@code invoice_ref_uuid} on QUEUED rows; null for CREATED_GL rows.
     */
    private String invoiceRefUuid;

    /** Invoice status: "QUEUED" for QUEUED_INVOICE rows; null for CREATED_GL rows */
    private String status;

    /** Data source tag: "QUEUED_INVOICE" or "CREATED_GL" */
    private String dataSource;

    /** Net cost amount in DKK (positive = cost to debtor company) */
    private double amountDkk;

    /** Description or text from the invoice or GL entry */
    private String description;
}

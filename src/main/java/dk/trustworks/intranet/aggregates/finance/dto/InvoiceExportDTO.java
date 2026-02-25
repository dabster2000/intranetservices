package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single invoice (or credit note) for the revenue source data export.
 * Used by the accumulated revenue source-data endpoint to provide raw invoice records
 * suitable for Excel export.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceExportDTO {

    /** Invoice UUID */
    private String uuid;

    /** Sequential invoice number */
    private int invoiceNumber;

    /** Invoice date in ISO format (YYYY-MM-DD) */
    private String invoiceDate;

    /** Client display name at the time of invoicing */
    private String clientName;

    /** UUID of the client */
    private String clientUuid;

    /** Project display name at the time of invoicing */
    private String projectName;

    /** UUID of the project */
    private String projectUuid;

    /** Invoice type: INVOICE, PHANTOM, or CREDIT_NOTE */
    private String type;

    /** Invoice status (e.g., CREATED) */
    private String status;

    /** Originating currency code (e.g., DKK, EUR, USD) */
    private String currency;

    /** Sum of (rate * hours) for all line items in the invoice's originating currency */
    private double originalAmount;

    /** Sum of (rate * hours) for all line items converted to DKK */
    private double amountDkk;

    /** Discount applied to the invoice (0–100 %) */
    private double discount;

    /** UUID of the Trustworks legal entity that issued this invoice */
    private String companyUuid;
}

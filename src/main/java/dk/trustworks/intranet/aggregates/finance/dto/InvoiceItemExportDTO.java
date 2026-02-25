package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single invoice line item for the revenue source data export.
 * One row per invoiceitem record — the detailed breakdown behind each invoice.
 * Used by the accumulated revenue source-data endpoint for Excel export.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItemExportDTO {

    /** Invoice item UUID */
    private String uuid;

    /** UUID of the parent invoice */
    private String invoiceUuid;

    /** Sequential invoice number of the parent invoice */
    private int invoiceNumber;

    /** Invoice date in ISO format (YYYY-MM-DD) */
    private String invoiceDate;

    /** Full name of the consultant (firstname + lastname), or itemname if no user match */
    private String consultantName;

    /** UUID of the consultant user, or empty string if not user-linked */
    private String consultantUuid;

    /** Item name as stored on the invoice line */
    private String itemName;

    /** Free-text description of the invoice line */
    private String description;

    /** Hourly/daily rate in the invoice's originating currency */
    private double rate;

    /** Quantity of hours/days billed */
    private double hours;

    /** rate * hours in the invoice's originating currency */
    private double amount;

    /** rate * hours converted to DKK using the period exchange rate */
    private double amountDkk;

    /** Origin type of the item (e.g., BASE, ADJUSTMENT) */
    private String origin;

    /** Client display name (denormalised from parent invoice / project) */
    private String clientName;

    /** Project display name (denormalised from parent invoice) */
    private String projectName;
}

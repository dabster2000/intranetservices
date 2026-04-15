package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter; import lombok.Setter;

import java.time.LocalDate;

/**
 * Q2C API v5.1.0 draft invoice payload — flat fields (no nested objects).
 * SPEC-INV-001 §6.4.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsDraftInvoice {
    private Integer  draftInvoiceNumber;   // returned by POST
    private Integer  customerNumber;
    private String   customerName;
    private LocalDate date;
    private String   currencyCode;
    private Integer  layoutNumber;
    private Integer  termOfPaymentNumber;
    private Integer  vatZoneNumber;
    // Recipient (flat)
    private String   customerAddress;
    private String   customerPostalCode;
    private String   customerCity;
    private String   customerCountry;
    // References + text
    private String   otherReference;
    private String   heading;
    private String   textLine1;
    private String   textLine2;
    private Integer  attentionNumber;      // flat Contact.number
    // Read-only fields echoed back:
    private LocalDate dueDate;
    private Double    grossAmount;
    private Double    netAmount;
}

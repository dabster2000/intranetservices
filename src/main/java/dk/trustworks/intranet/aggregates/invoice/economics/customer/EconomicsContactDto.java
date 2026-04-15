package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Read/write subset of the e-conomic Customers API v3.1.0 Contact resource.
 * Flat JSON shape; unknown fields are ignored so the DTO survives e-conomic
 * schema drift.
 *
 * <p>Customers v3.1.0 uses {@code customerContactNumber} as the server-assigned
 * identifier for a contact (confirmed by Phase G0 probe). The same value is
 * surfaced as {@code attentionNumber} when referenced from Q2C drafts
 * (SPEC-INV-001 §6.4).
 *
 * SPEC-INV-001 §3.3.2, §6.1, §7.1 Phase G2.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsContactDto {

    /** FK to the parent customer; flat in Customers v3.1.0. */
    private Integer customerNumber;

    /** Server-assigned on POST. */
    private Integer customerContactNumber;

    private String name;
    private String email;
    private Boolean receiveInvoices;
    private Boolean receiveEInvoices;
    private String eInvoiceId;
    private String objectVersion;
}

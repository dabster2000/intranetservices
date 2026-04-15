package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * <p><b>NON_NULL</b> serialisation — e-conomic rejects explicit {@code null}
 * on typed Boolean/Integer fields (verified 2026-04-15). Same discipline as
 * {@link EconomicsCustomerDto}.
 *
 * SPEC-INV-001 §3.3.2, §6.1, §7.1 Phase G2.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
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

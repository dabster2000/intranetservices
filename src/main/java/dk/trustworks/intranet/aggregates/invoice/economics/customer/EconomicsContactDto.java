package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Read/write subset of the e-conomic Customers API v3.1.0 Contact resource.
 * Flat JSON shape; unknown fields are ignored so the DTO survives e-conomic
 * schema drift.
 *
 * <p>On the wire the server-assigned contact identifier is {@code number}
 * (Contact schema + Phase G0 probe {@code g0-3-contacts-filter.http.json}) —
 * mapped here as {@code customerContactNumber} to match the
 * {@code client_economics_contacts} column name. {@code PUT /Contacts} relies
 * on it to address the contact being updated. The same value is surfaced as
 * {@code attentionNumber} when referenced from Q2C drafts (SPEC-INV-001 §6.4).
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

    /** Server-assigned on POST; wire name is {@code number}. */
    @JsonProperty("number")
    private Integer customerContactNumber;

    private String name;
    private String email;
    private Boolean receiveInvoices;
    private Boolean receiveEInvoices;
    private String eInvoiceId;
    private String objectVersion;
}

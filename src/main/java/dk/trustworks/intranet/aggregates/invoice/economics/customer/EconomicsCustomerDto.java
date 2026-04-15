package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Read-side subset of the e-conomic Customers API v3.1.0 Customer resource.
 * Only the fields used by pairing and display are deserialised; unknown fields
 * are ignored so the DTO survives e-conomic schema drift.
 *
 * SPEC-INV-001 §6.3.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsCustomerDto {
    private Integer customerNumber;
    private String name;
    private String cvrNo;
    private Boolean access;
    private String objectVersion;

    /** Customer group number (required by POST /Customers; flat in e-conomic JSON). */
    private Integer customerGroupNumber;

    /** VAT zone number (e-conomic calls this "zone" in Customers v3.1.0). */
    private Integer zone;

    /** ISO-4217 currency code used as default for invoices against this customer. */
    private String currency;

    /** Payment terms number as configured in e-conomic (paymentTermId in v3.1.0). */
    private Integer paymentTermId;
}

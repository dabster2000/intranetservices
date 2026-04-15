package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Read/write subset of the e-conomic Customers API v3.1.0 Customer resource.
 * Only the fields used by pairing, display, and Phase G2 sync are serialised;
 * unknown fields are ignored so the DTO survives e-conomic schema drift.
 *
 * <p>Field set covers the required-on-POST fields plus the full Phase G2 sync
 * payload (§6.3): address1/address2, postCode, city, country, email,
 * eanLocationNumber, nemHandelReceiverType, defaultDisableEInvoicing.
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

    // --------------------------------------------------- Phase G2 sync fields

    /** Address line 1 (max 255 — {@link #address2} carries any overflow). */
    private String address1;

    /** Address line 2 / overflow for long addresses (max 255). */
    private String address2;

    /** Postal code, e-conomic field: postCode (max 30). */
    private String postCode;

    /** City (max 50). */
    private String city;

    /** Country as the English display name expected by e-conomic (max 50). */
    private String country;

    /** Customer email (max 255). */
    private String email;

    /** Phone number (max 50). */
    private String phone;

    /** EAN location number for NemHandel (max 13). */
    private String eanLocationNumber;

    /**
     * NemHandel receiver type. Customers v3.1.0 value mapping:
     * 1 = EAN, plus CVR / P-number / PEPPOL values (V2). §6.3.
     */
    private Integer nemHandelReceiverType;

    /** Whether e-invoicing is disabled by default — set to {@code false} when EAN present. */
    private Boolean defaultDisableEInvoicing;
}

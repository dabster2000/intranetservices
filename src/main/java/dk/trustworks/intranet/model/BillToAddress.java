package dk.trustworks.intranet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured billing address for invoices.
 * Used for JSON serialization/deserialization between backend and frontend.
 *
 * @since V2 Migration 2025-11-05
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillToAddress {

    /** Name of the billing entity (company or organization). */
    public String name;

    /** Attention line for specific person or department (optional). */
    public String attn;

    /** Primary address line (street address). */
    public String line1;

    /** Additional address information such as building, floor, suite (optional). */
    public String line2;

    /** Postal code. */
    public String zip;

    /** City name. */
    public String city;

    /** ISO 3166-1 alpha-2 country code (e.g., DK=Denmark, SE=Sweden, NO=Norway). */
    public String country;

    /** Electronic Account Number for electronic invoicing (optional). */
    public String ean;

    /** CVR (Denmark) or equivalent VAT/business registration number (optional). */
    public String cvr;
}

package dk.trustworks.intranet.dao.crm.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response DTO representing a company record from the Danish CVR registry.
 *
 * <p>Sourced from the Virkdata API (virkdata.dk). Field names and JSON shape
 * are kept stable for the frontend: {@code vat}, {@code name}, {@code address},
 * {@code zipcode}, {@code city}, {@code phone}, {@code email}, {@code industrycode},
 * {@code industrydesc}, {@code companycode}, {@code companydesc}.
 *
 * <p>Note: Virkdata does not return a legal-form code (Danish "virksomhedsform").
 * {@code companycode} is always {@code 0}; use {@code companydesc} for display.
 * The field is kept in the response shape for frontend compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Company data from the Danish CVR registry (virkdata.dk)")
public class CvrApiResponse {

    @Schema(description = "CVR number (8-digit Danish company registration number)", example = "35648941")
    public long vat;

    @Schema(description = "Registered company name", example = "TRUSTWORKS A/S")
    public String name;

    @Schema(description = "Street address", example = "Pustervig 3 4")
    public String address;

    @Schema(description = "Postal code", example = "1126")
    public String zipcode;

    @Schema(description = "City name", example = "København K")
    public String city;

    @JsonProperty("protected")
    @Schema(description = "Whether the company is marked as advertisement-protected in CVR")
    public boolean isProtected;

    @Schema(description = "Business phone number", example = "71992999")
    public String phone;

    @Schema(description = "Business email address", example = "hello@trustworks.dk")
    public String email;

    @Schema(description = "Company founding date (YYYY-MM-DD)", example = "2014-01-27")
    public String startdate;

    @Schema(description = "Danish industry classification code (branchekode)", example = "622000")
    public int industrycode;

    @Schema(description = "Industry classification description", example = "Computerkonsulentbistand og forvaltning af computerfaciliteter")
    public String industrydesc;

    @Schema(description = "Legal-form code — always 0; Virkdata does not expose this. Use companydesc instead.", example = "0")
    public int companycode;

    @Schema(description = "Legal form description", example = "Aktieselskab")
    public String companydesc;

    @Schema(description = "Error code if the lookup failed (null on success). Reserved for error payloads.")
    public String error;

    /**
     * Returns true if this response represents an error (no company data).
     */
    public boolean hasError() {
        return error != null && !error.isBlank();
    }
}

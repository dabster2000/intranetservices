package dk.trustworks.intranet.dao.crm.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response DTO representing a company record from the CVR API (cvrapi.dk).
 *
 * <p>Maps all fields from the CVR API JSON response. The {@code error} field
 * is non-null when the API returns an error instead of company data.
 *
 * <p>Note: {@code protected} is a Java reserved word, so we use
 * {@code isProtected} with {@code @JsonProperty("protected")}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Company data from the Danish CVR registry (cvrapi.dk)")
public class CvrApiResponse {

    @Schema(description = "CVR number (8-digit Danish company registration number)", example = "25674114")
    public long vat;

    @Schema(description = "Registered company name", example = "Trustworks A/S")
    public String name;

    @Schema(description = "Street address", example = "Borgergade 14, 3.")
    public String address;

    @Schema(description = "Postal code", example = "1300")
    public String zipcode;

    @Schema(description = "City name", example = "Copenhagen K")
    public String city;

    @Schema(description = "Extended city name (API v6+)")
    public String cityname;

    @JsonProperty("protected")
    @Schema(description = "Whether the company name/address is protected")
    public boolean isProtected;

    @Schema(description = "Business phone number", example = "71992999")
    public String phone;

    @Schema(description = "Business email address", example = "info@trustworks.dk")
    public String email;

    @Schema(description = "Company founding date", example = "04/01 - 2014")
    public String startdate;

    @Schema(description = "Danish industry classification code (branchekode)", example = "620100")
    public int industrycode;

    @Schema(description = "Industry classification description", example = "Computerprogrammering")
    public String industrydesc;

    @Schema(description = "Legal form code (10=A/S, 80=ApS, etc.)", example = "10")
    public int companycode;

    @Schema(description = "Legal form description", example = "Aktieselskab")
    public String companydesc;

    @Schema(description = "API response version")
    public int version;

    @Schema(description = "Error code if the lookup failed (null on success)")
    public String error;

    /**
     * Returns true if this response represents an error (no company data).
     */
    public boolean hasError() {
        return error != null && !error.isBlank();
    }
}

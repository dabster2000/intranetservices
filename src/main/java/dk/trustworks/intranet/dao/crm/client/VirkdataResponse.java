package dk.trustworks.intranet.dao.crm.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Internal DTO matching the raw JSON shape returned by the Virkdata API
 * (virkdata.dk). See {@code docs/external-apis/virk/virk-api-1.4.10.md}.
 *
 * <p>Virkdata returns either a success payload (with company fields) or an
 * error payload (with {@code error_code}, {@code response}, {@code message}).
 * Both are tolerated here; {@link #hasError()} discriminates.
 *
 * <p><strong>Observed field types (from live API, 2026-04-20):</strong>
 * {@code vat}, {@code zipcode}, and {@code industrycode} are returned as JSON
 * strings even though the public docs describe them as integers. Modelled as
 * {@code String} here to avoid deserialization failures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VirkdataResponse {

    public String vat;
    public String status;
    public String name;
    public String address;
    public String zipcode;
    public String city;

    @JsonProperty("protected")
    public boolean isProtected;

    public String phone;
    public String email;
    public String website;
    public String fax;
    public String startdate;
    public String enddate;
    public Integer employees;
    public String industrycode;
    public String industrydesc;
    public String companytype;
    public String companydesc;
    public List<String> owners;

    @JsonProperty("error_code")
    public Integer errorCode;

    public String response;
    public String message;

    public boolean hasError() {
        return errorCode != null;
    }
}

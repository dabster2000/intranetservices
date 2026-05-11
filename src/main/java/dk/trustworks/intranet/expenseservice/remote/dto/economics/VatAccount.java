package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"vatCode"})
public class VatAccount {

    @JsonProperty("vatCode")
    public String vatCode;

    public VatAccount() {}

    public VatAccount(String vatCode) {
        this.vatCode = vatCode;
    }

    @JsonProperty("vatCode")
    public String getVatCode() {
        return vatCode;
    }

    @JsonProperty("vatCode")
    public void setVatCode(String vatCode) {
        this.vatCode = vatCode;
    }

    @Override
    public String toString() {
        return "VatAccount{vatCode='" + vatCode + "'}";
    }
}

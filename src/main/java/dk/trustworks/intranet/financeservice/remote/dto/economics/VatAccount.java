
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.*;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "vatCode",
    "self"
})
public class VatAccount {

    @JsonProperty("vatCode")
    private String vatCode;
    @JsonProperty("self")
    private String self;
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     * 
     */
    public VatAccount() {
    }

    /**
     * 
     * @param self
     * @param vatCode
     */
    public VatAccount(String vatCode, String self) {
        super();
        this.vatCode = vatCode;
        this.self = self;
    }

    @JsonProperty("vatCode")
    public String getVatCode() {
        return vatCode;
    }

    @JsonProperty("vatCode")
    public void setVatCode(String vatCode) {
        this.vatCode = vatCode;
    }

    @JsonProperty("self")
    public String getSelf() {
        return self;
    }

    @JsonProperty("self")
    public void setSelf(String self) {
        this.self = self;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

    @Override
    public String toString() {
        return "VatAccount{" +
                "vatCode='" + vatCode + '\'' +
                ", self='" + self + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}

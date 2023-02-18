
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.*;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "accountNumber",
    "self"
})
public class Account {

    @JsonProperty("accountNumber")
    private int accountNumber;
    @JsonProperty("self")
    private String self;
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     * 
     */
    public Account() {
    }

    /**
     * 
     * @param self
     * @param accountNumber
     */
    public Account(int accountNumber, String self) {
        super();
        this.accountNumber = accountNumber;
        this.self = self;
    }

    @JsonProperty("accountNumber")
    public int getAccountNumber() {
        return accountNumber;
    }

    @JsonProperty("accountNumber")
    public void setAccountNumber(int accountNumber) {
        this.accountNumber = accountNumber;
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
        return "Account{" +
                "accountNumber=" + accountNumber +
                ", self='" + self + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}


package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "accountNumber",
        "self"
})
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class Account {

    @JsonProperty("accountNumber")
    public int accountNumber;
    @JsonProperty("self")
    public String self;
}

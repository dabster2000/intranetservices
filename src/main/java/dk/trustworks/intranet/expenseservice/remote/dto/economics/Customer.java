package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "customerNumber",
        "self"
})
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class Customer {

    @JsonProperty("customerNumber")
    public int customerNumber;
    @JsonProperty("self")
    public String self;

}

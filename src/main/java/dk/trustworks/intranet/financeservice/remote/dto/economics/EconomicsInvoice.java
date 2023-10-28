
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "collection",
        "pagination",
        "self"
})
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EconomicsInvoice {

    @JsonProperty("collection")
    public List<Collection> collection = new ArrayList<Collection>();
    @JsonProperty("pagination")
    public Pagination pagination;
    @JsonProperty("self")
    public String self;

}
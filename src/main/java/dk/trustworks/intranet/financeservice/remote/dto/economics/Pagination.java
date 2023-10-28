
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "skipPages",
        "pageSize",
        "maxPageSizeAllowed",
        "results",
        "resultsWithoutFilter",
        "firstPage",
        "nextPage",
        "lastPage"
})
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class Pagination {

    @JsonProperty("skipPages")
    public int skipPages;
    @JsonProperty("pageSize")
    public int pageSize;
    @JsonProperty("maxPageSizeAllowed")
    public int maxPageSizeAllowed;
    @JsonProperty("results")
    public int results;
    @JsonProperty("resultsWithoutFilter")
    public int resultsWithoutFilter;
    @JsonProperty("firstPage")
    public String firstPage;
    @JsonProperty("nextPage")
    public String nextPage;
    @JsonProperty("lastPage")
    public String lastPage;

}

package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.*;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "maxPageSizeAllowed",
    "skipPages",
    "pageSize",
    "results",
    "resultsWithoutFilter",
    "firstPage",
        "nextPage",
    "lastPage"
})
public class Pagination {

    @JsonProperty("maxPageSizeAllowed")
    private int maxPageSizeAllowed;
    @JsonProperty("skipPages")
    private int skipPages;
    @JsonProperty("pageSize")
    private int pageSize;
    @JsonProperty("results")
    private int results;
    @JsonProperty("resultsWithoutFilter")
    private int resultsWithoutFilter;
    @JsonProperty("firstPage")
    private String firstPage;
    @JsonProperty("nextPage")
    private String nextPage;
    @JsonProperty("lastPage")
    private String lastPage;
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     * 
     */
    public Pagination() {
    }

    /**
     * 
     * @param firstPage
     * @param nextPage
     * @param lastPage
     * @param maxPageSizeAllowed
     * @param resultsWithoutFilter
     * @param skipPages
     * @param pageSize
     * @param results
     */
    public Pagination(int maxPageSizeAllowed, int skipPages, int pageSize, int results, int resultsWithoutFilter, String firstPage, String nextPage, String lastPage) {
        super();
        this.maxPageSizeAllowed = maxPageSizeAllowed;
        this.skipPages = skipPages;
        this.pageSize = pageSize;
        this.results = results;
        this.resultsWithoutFilter = resultsWithoutFilter;
        this.firstPage = firstPage;
        this.nextPage = nextPage;
        this.lastPage = lastPage;
    }

    @JsonProperty("maxPageSizeAllowed")
    public int getMaxPageSizeAllowed() {
        return maxPageSizeAllowed;
    }

    @JsonProperty("maxPageSizeAllowed")
    public void setMaxPageSizeAllowed(int maxPageSizeAllowed) {
        this.maxPageSizeAllowed = maxPageSizeAllowed;
    }

    @JsonProperty("skipPages")
    public int getSkipPages() {
        return skipPages;
    }

    @JsonProperty("skipPages")
    public void setSkipPages(int skipPages) {
        this.skipPages = skipPages;
    }

    @JsonProperty("pageSize")
    public int getPageSize() {
        return pageSize;
    }

    @JsonProperty("pageSize")
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    @JsonProperty("results")
    public int getResults() {
        return results;
    }

    @JsonProperty("results")
    public void setResults(int results) {
        this.results = results;
    }

    @JsonProperty("resultsWithoutFilter")
    public int getResultsWithoutFilter() {
        return resultsWithoutFilter;
    }

    @JsonProperty("resultsWithoutFilter")
    public void setResultsWithoutFilter(int resultsWithoutFilter) {
        this.resultsWithoutFilter = resultsWithoutFilter;
    }

    @JsonProperty("firstPage")
    public String getFirstPage() {
        return firstPage;
    }

    @JsonProperty("firstPage")
    public void setFirstPage(String firstPage) {
        this.firstPage = firstPage;
    }

    @JsonProperty("nextPage")
    public String getNextPage() {
        return nextPage;
    }

    @JsonProperty("nextPage")
    public void setNextPage(String nextPage) {
        this.nextPage = nextPage;
    }

    @JsonProperty("lastPage")
    public String getLastPage() {
        return lastPage;
    }

    @JsonProperty("lastPage")
    public void setLastPage(String lastPage) {
        this.lastPage = lastPage;
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
        return "Pagination{" +
                "maxPageSizeAllowed=" + maxPageSizeAllowed +
                ", skipPages=" + skipPages +
                ", pageSize=" + pageSize +
                ", results=" + results +
                ", resultsWithoutFilter=" + resultsWithoutFilter +
                ", firstPage='" + firstPage + '\'' +
                ", nextPage='" + nextPage + '\'' +
                ", lastPage='" + lastPage + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}

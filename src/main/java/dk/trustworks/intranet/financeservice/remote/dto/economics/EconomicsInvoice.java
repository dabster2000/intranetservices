
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "collection",
    "pagination",
    "self"
})
public class EconomicsInvoice {

    @JsonProperty("collection")
    private List<Collection> collection = null;
    @JsonProperty("pagination")
    private Pagination pagination;
    @JsonProperty("self")
    private String self;
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     * 
     */
    public EconomicsInvoice() {
    }

    /**
     * 
     * @param pagination
     * @param self
     * @param collection
     */
    public EconomicsInvoice(List<Collection> collection, Pagination pagination, String self) {
        super();
        this.collection = collection;
        this.pagination = pagination;
        this.self = self;
    }

    @JsonProperty("collection")
    public List<Collection> getCollection() {
        return collection;
    }

    @JsonProperty("collection")
    public void setCollection(List<Collection> collection) {
        this.collection = collection;
    }

    @JsonProperty("pagination")
    public Pagination getPagination() {
        return pagination;
    }

    @JsonProperty("pagination")
    public void setPagination(Pagination pagination) {
        this.pagination = pagination;
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
        return "EconomicsInvoice{" +
                "collection=" + collection +
                ", pagination=" + pagination +
                ", self='" + self + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}

package dk.trustworks.intranet.userservice.model.jwt;

import com.fasterxml.jackson.annotation.*;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "group1",
        "group2"
})
public class RoleMappings {

    @JsonProperty("group1")
    private String group1;
    @JsonProperty("group2")
    private String group2;
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     *
     */
    public RoleMappings() {
    }

    /**
     *
     * @param group2
     * @param group1
     */
    public RoleMappings(String group1, String group2) {
        super();
        this.group1 = group1;
        this.group2 = group2;
    }

    @JsonProperty("group1")
    public String getGroup1() {
        return group1;
    }

    @JsonProperty("group1")
    public void setGroup1(String group1) {
        this.group1 = group1;
    }

    @JsonProperty("group2")
    public String getGroup2() {
        return group2;
    }

    @JsonProperty("group2")
    public void setGroup2(String group2) {
        this.group2 = group2;
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
        return "RoleMappings{" +
                "group1='" + group1 + '\'' +
                ", group2='" + group2 + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}

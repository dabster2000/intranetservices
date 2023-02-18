package dk.trustworks.intranet.userservice.model.jwt;

import com.fasterxml.jackson.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "iss",
        "jti",
        "sub",
        "upn",
        "preferred_username",
        "aud",
        "birthdate",
        "roleMappings",
        "groups"
})
public class JwtClaims {

    @JsonProperty("iss")
    private String iss;
    @JsonProperty("jti")
    private String jti;
    @JsonProperty("sub")
    private String sub;
    @JsonProperty("upn")
    private String upn;
    @JsonProperty("preferred_username")
    private String preferredUsername;
    @JsonProperty("aud")
    private String aud;
    @JsonProperty("birthdate")
    private String birthdate;
    @JsonProperty("roleMappings")
    private RoleMappings roleMappings;
    @JsonProperty("groups")
    private List<String> groups = new ArrayList<String>();
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     *
     */
    public JwtClaims() {
    }

    /**
     *
     * @param sub
     * @param aud
     * @param upn
     * @param birthdate
     * @param preferredUsername
     * @param iss
     * @param roleMappings
     * @param groups
     * @param jti
     */
    public JwtClaims(String iss, String jti, String sub, String upn, String preferredUsername, String aud, String birthdate, RoleMappings roleMappings, List<String> groups) {
        super();
        this.iss = iss;
        this.jti = jti;
        this.sub = sub;
        this.upn = upn;
        this.preferredUsername = preferredUsername;
        this.aud = aud;
        this.birthdate = birthdate;
        this.roleMappings = roleMappings;
        this.groups = groups;
    }

    @JsonProperty("iss")
    public String getIss() {
        return iss;
    }

    @JsonProperty("iss")
    public void setIss(String iss) {
        this.iss = iss;
    }

    @JsonProperty("jti")
    public String getJti() {
        return jti;
    }

    @JsonProperty("jti")
    public void setJti(String jti) {
        this.jti = jti;
    }

    @JsonProperty("sub")
    public String getSub() {
        return sub;
    }

    @JsonProperty("sub")
    public void setSub(String sub) {
        this.sub = sub;
    }

    @JsonProperty("upn")
    public String getUpn() {
        return upn;
    }

    @JsonProperty("upn")
    public void setUpn(String upn) {
        this.upn = upn;
    }

    @JsonProperty("preferred_username")
    public String getPreferredUsername() {
        return preferredUsername;
    }

    @JsonProperty("preferred_username")
    public void setPreferredUsername(String preferredUsername) {
        this.preferredUsername = preferredUsername;
    }

    @JsonProperty("aud")
    public String getAud() {
        return aud;
    }

    @JsonProperty("aud")
    public void setAud(String aud) {
        this.aud = aud;
    }

    @JsonProperty("birthdate")
    public String getBirthdate() {
        return birthdate;
    }

    @JsonProperty("birthdate")
    public void setBirthdate(String birthdate) {
        this.birthdate = birthdate;
    }

    @JsonProperty("roleMappings")
    public RoleMappings getRoleMappings() {
        return roleMappings;
    }

    @JsonProperty("roleMappings")
    public void setRoleMappings(RoleMappings roleMappings) {
        this.roleMappings = roleMappings;
    }

    @JsonProperty("groups")
    public List<String> getGroups() {
        return groups;
    }

    @JsonProperty("groups")
    public void setGroups(List<String> groups) {
        this.groups = groups;
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
        return "JwtClaims{" +
                "iss='" + iss + '\'' +
                ", jti='" + jti + '\'' +
                ", sub='" + sub + '\'' +
                ", upn='" + upn + '\'' +
                ", preferredUsername='" + preferredUsername + '\'' +
                ", aud='" + aud + '\'' +
                ", birthdate='" + birthdate + '\'' +
                ", roleMappings=" + roleMappings +
                ", groups=" + groups +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}

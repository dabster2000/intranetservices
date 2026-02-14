package dk.trustworks.intranet.cvtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Full employee response from CV Tool GET /cv/employee/{id}.
 * The CV field contains the complete base CV as a nested JSON object
 * including educations, competencies, certifications, languages, and projects.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CvToolEmployeeResponse(
    @JsonProperty("ID") int id,
    @JsonProperty("Name") String name,
    @JsonProperty("Mail") String mail,
    @JsonProperty("Phone") String phone,
    @JsonProperty("Employee_UUID") String employeeUuid,
    @JsonProperty("Is_Deleted") boolean isDeleted,
    @JsonProperty("Last_Updated_At") String lastUpdatedAt,
    @JsonProperty("Created_At") String createdAt,
    @JsonProperty("CV") JsonNode cv
) {

    /**
     * Extracts the CV_Language field from the embedded CV object.
     */
    public int cvLanguage() {
        if (cv == null || cv.isNull()) return 1;
        JsonNode lang = cv.get("CV_Language");
        return lang != null && !lang.isNull() ? lang.asInt(1) : 1;
    }

    /**
     * Extracts the CV ID from the embedded CV object.
     */
    public int cvId() {
        if (cv == null || cv.isNull()) return -1;
        JsonNode id = cv.get("ID");
        return id != null && !id.isNull() ? id.asInt(-1) : -1;
    }

    /**
     * Extracts Employee_Title from the embedded CV object.
     */
    public String employeeTitle() {
        if (cv == null || cv.isNull()) return null;
        JsonNode title = cv.get("Employee_Title");
        return title != null && !title.isNull() ? title.asText(null) : null;
    }

    /**
     * Extracts Employee_Profile from the embedded CV object.
     */
    public String employeeProfile() {
        if (cv == null || cv.isNull()) return null;
        JsonNode profile = cv.get("Employee_Profile");
        return profile != null && !profile.isNull() ? profile.asText(null) : null;
    }
}

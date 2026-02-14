package dk.trustworks.intranet.cvtool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight employee from CV Tool GET /cv/employees.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CvToolEmployeeSkinny(
    @JsonProperty("ID") int id,
    @JsonProperty("Name") String name,
    @JsonProperty("Is_Deleted") boolean isDeleted,
    @JsonProperty("Employee_UUID") String employeeUuid
) {}

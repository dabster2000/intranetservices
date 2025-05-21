package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RegistrationRequest {

    @JsonProperty("guestName")
    private String guestName;

    @JsonProperty("guestCompany")
    private String guestCompany;

    @JsonProperty("employee")
    private String employee;

    @JsonProperty("employeeId")
    private String employeeId;

    @JsonProperty("timestamp")
    private String timestamp;
}
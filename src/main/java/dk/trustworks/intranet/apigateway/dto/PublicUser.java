package dk.trustworks.intranet.apigateway.dto;

import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PublicUser {

    private String uuid;
    private boolean active;
    private ConsultantType type;
    private String firstName;
    private String lastName;
    private LocalDate birthday;

}

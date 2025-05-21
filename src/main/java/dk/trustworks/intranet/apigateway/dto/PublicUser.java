package dk.trustworks.intranet.apigateway.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.userservice.model.Employee;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PublicUser {

    private String uuid;
    private boolean active;
    private ConsultantType type;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate birthday;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate hireDate;

    public PublicUser(Employee employee) {
        setUuid(employee.getUuid());
        setActive(!(employee.getStatus().equals(StatusType.TERMINATED) || employee.getStatus().equals(StatusType.PREBOARDING)));
        setType(employee.getConsultanttype());
        setFirstName(employee.getFirstname());
        setLastName(employee.getLastname());
        setEmail(employee.getEmail());
        setPhone(employee.getPhone());
        setBirthday(DateUtils.getNextBirthday(employee.getBirthday()));
        setHireDate(employee.getHireDate());
    }

}

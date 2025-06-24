package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "guest_registration")
public class GuestRegistration extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", columnDefinition = "char(36)")
    private String uuid;

    @Column(name = "guest_name")
    private String guestName;

    @Column(name = "guest_company")
    private String guestCompany;

    @Column(name = "employee_uuid")
    private String employeeUuid;

    @Column(name = "employee_name")
    private String employeeName;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @Column(name = "registration_time")
    private LocalDateTime registrationTime;
}

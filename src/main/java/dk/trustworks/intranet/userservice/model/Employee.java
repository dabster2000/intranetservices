package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.PrimarySkillType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Created by hans on 23/06/2017.
 */

@Data
@NoArgsConstructor
@Entity
@Table(name = "consultant")
public class Employee extends PanacheEntityBase {

    @Id
    public String uuid;
    private boolean active;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate created;
    private String email;
    private String firstname;
    private String lastname;
    private String gender;
    @JsonIgnore
    private String type;
    @JsonIgnore
    private String password;
    private String username;
    private String slackusername;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate birthday;
    private String cpr;
    private String phone;
    private boolean pension;
    private boolean healthcare;
    private String pensiondetails;
    private String defects;
    private boolean photoconsent;
    private String other;
    @Enumerated(EnumType.STRING)
    private PrimarySkillType primaryskilltype;
    @Column(name = "primary_skill_level")
    private int primaryskilllevel;
    @Enumerated(EnumType.STRING)
    private StatusType status;
    private int allocation;
    @Enumerated(EnumType.STRING)
    private ConsultantType consultanttype;
    private int salary;
    @Column(name = "hiredate")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate hireDate;
    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "uuid")
    private User user;
    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Employee user = (Employee) o;
        return getUuid().equals(user.getUuid());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getUuid());
    }

}

package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 23/06/2017.
 */

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "salary")
public class Salary extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Min(message = "Salary must be higher or equal to zero", value = 0)
    private int salary;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate activefrom;

    private String useruuid;

    public Salary() {
    }

    public Salary(LocalDate activeFrom, int salary, String useruuid) {
        this.useruuid = useruuid;
        uuid = UUID.randomUUID().toString();
        this.activefrom = activeFrom;
        this.salary = salary;
    }

    public Salary(String uuid, int salary, LocalDate activefrom, String useruuid) {
        this.uuid = uuid;
        this.salary = salary;
        this.activefrom = activefrom;
        this.useruuid = useruuid;
    }

    public static List<Salary> findByUseruuid(String useruuid) {
        List<Salary> salaries = find("useruuid", useruuid).list();
        return salaries; // salaries.stream().peek(s -> s.setSalary(0)).collect(Collectors.toList());
    }
}

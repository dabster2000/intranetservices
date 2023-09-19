package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 23/06/2017.
 */

@Entity
@Table(name = "salary")
public class Salary extends PanacheEntityBase {

    @Id
    private String uuid;

    @Min(message="Salary must be higher or equal to zero", value=0)
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

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public int getSalary() {
        return salary;
    }

    public void setSalary(int salary) {
        this.salary = salary;
    }

    public LocalDate getActivefrom() {
        return activefrom;
    }

    public void setActivefrom(LocalDate activefrom) {
        this.activefrom = activefrom;
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
        this.useruuid = useruuid;
    }

    public static List<Salary> findByUseruuid(String useruuid){
        List<Salary> salaries = find("useruuid", useruuid).list();
        return salaries; // salaries.stream().peek(s -> s.setSalary(0)).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "Salary{" +
                "uuid='" + uuid + '\'' +
                ", salary=" + salary +
                ", activefrom=" + activefrom +
                ", useruuid='" + useruuid + '\'' +
                '}';
    }
}

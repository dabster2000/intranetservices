package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.Min;
import org.hibernate.annotations.ColumnDefault;

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

    private String useruuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate activefrom;

    @Size(max = 30)
    @NotNull
    @ColumnDefault("MONTHLY")
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private SalaryType type;

    @Min(message = "Salary must be higher or equal to zero", value = 0)
    private int salary;

    private boolean lunch;
    private boolean phone;
    private boolean internet;
    @Column(name = "prayer_day")
    private boolean prayerDay;

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
        return find("useruuid", useruuid).list();
    }

    public double calculateActualWorkHours(double daysInMonth, double actualWorkDays) {
        double monthNormHours = 160.33;
        double dailyNormHours = monthNormHours / daysInMonth;
        return actualWorkDays * dailyNormHours;
    }

    public double calculateMonthNormAdjustedSalary(double daysInMonth, double actualWorkDays) {
        double monthNormHours = 160.33;
        return (calculateActualWorkHours(daysInMonth, actualWorkDays) / monthNormHours) * salary;
    }
}

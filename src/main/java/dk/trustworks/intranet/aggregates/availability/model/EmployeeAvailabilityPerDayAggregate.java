package dk.trustworks.intranet.aggregates.availability.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bi_availability_per_day")
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class EmployeeAvailabilityPerDayAggregate extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "document_date")
    private LocalDate documentDate; // done

    private int year;
    private int month;
    private int day;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    @JsonProperty("user")
    private User user; // done

    @Column(name = "gross_available_hours", precision = 7, scale = 4)
    @JsonProperty("grossAvailableHours")
    // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    private BigDecimal grossAvailableHours; // done

    @Column(name = "unavailable_hours", precision = 7, scale = 4)
    @JsonProperty("unavailableHours")
    private BigDecimal unavavailableHours; // fx fridays

    @Column(name = "vacation_hours", precision = 7, scale = 4)
    @JsonProperty("vacationHours")
    private BigDecimal vacationHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "sick_hours", precision = 7, scale = 4)
    @JsonProperty("sickHours")
    private BigDecimal sickHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "maternity_leave_hours", precision = 7, scale = 4)
    @JsonProperty("maternityLeaveHours")
    private BigDecimal maternityLeaveHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "non_payd_leave_hours", precision = 7, scale = 4)
    @JsonProperty("nonPaydLeaveHours")
    private BigDecimal nonPaydLeaveHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "paid_leave_hours", precision = 7, scale = 4)
    @JsonProperty("paidLeaveHours")
    private BigDecimal paidLeaveHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "consultant_type")
    @JsonProperty("consultantType")
    @Enumerated(EnumType.STRING)
    private ConsultantType consultantType; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "status_type")
    @JsonProperty("statusType")
    @Enumerated(EnumType.STRING)
    private StatusType statusType; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "salary")
    @JsonProperty("salary")
    private int salary;

    @Column(name = "last_update")
    @JsonIgnore
    private LocalDateTime lastUpdate;

    @Column(name = "is_tw_bonus_eligible")
    @JsonProperty("isTwBonusEligible")
    private boolean isTwBonusEligible;

    public EmployeeAvailabilityPerDayAggregate(Company company, LocalDate documentDate, User user, double grossAvailableHours, double unavailableHours, double vacationHours, double sickHours, double maternityLeaveHours, double nonPaydLeaveHours, double paidLeaveHours, ConsultantType consultantType, StatusType statusType, int salary, boolean isTwBonusEligible) {
        this.company = company;
        this.lastUpdate = LocalDateTime.now();
        this.documentDate = documentDate;
        this.year = documentDate.getYear();
        this.month = documentDate.getMonthValue();
        this.day = documentDate.getDayOfMonth();
        this.user = user;
        this.grossAvailableHours = BigDecimal.valueOf(grossAvailableHours);
        this.unavavailableHours = BigDecimal.valueOf(unavailableHours);
        this.vacationHours = BigDecimal.valueOf(vacationHours);
        this.sickHours = BigDecimal.valueOf(sickHours);
        this.maternityLeaveHours = BigDecimal.valueOf(maternityLeaveHours);
        this.nonPaydLeaveHours = BigDecimal.valueOf(nonPaydLeaveHours);
        this.paidLeaveHours = BigDecimal.valueOf(paidLeaveHours);
        this.consultantType = consultantType;
        this.statusType = statusType;
        this.salary = salary;
        this.isTwBonusEligible = isTwBonusEligible;
        if(statusType.equals(StatusType.TERMINATED) || statusType.equals(StatusType.PREBOARDING) || statusType.equals(StatusType.NON_PAY_LEAVE)) {
            this.salary = 0;
            this.isTwBonusEligible = false;
        }
    }

    @Transient
    @JsonIgnore
    public Double getNetAvailableHours() {
        return grossAvailableHours!=null?Math.max(grossAvailableHours.doubleValue() - unavavailableHours.doubleValue() - vacationHours.doubleValue() - sickHours.doubleValue()- maternityLeaveHours.doubleValue() - nonPaydLeaveHours.doubleValue() - paidLeaveHours.doubleValue(), 0.0):0.0;
    }


    @Override
    public String toString() {
        return "AvailabilityPerDayDocument{" +
                "month=" + documentDate +
                ", grossAvailableHours=" + grossAvailableHours +
                ", vacationHours=" + vacationHours +
                ", sickHours=" + sickHours +
                ", maternityLeaveHours=" + maternityLeaveHours +
                ", nonPaydLeaveHoursPerday=" + nonPaydLeaveHours +
                ", paidLeaveHoursPerDay=" + paidLeaveHours +
                ", consultantType=" + consultantType +
                ", statusType=" + statusType +
                ", netAvailableHours=" + getNetAvailableHours() +
                '}';
    }
}

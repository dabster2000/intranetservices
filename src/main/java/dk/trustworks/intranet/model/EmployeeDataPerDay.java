package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "availability_document")
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeDataPerDay extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    @JsonProperty("month")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate month; // done

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

    @Column(name = "registered_billable_hours", precision = 7, scale = 4)
    private BigDecimal registeredBillableHours; // done

    @Column(name = "helped_colleague_billable_hours", precision = 7, scale = 4)
    private BigDecimal helpedColleagueBillableHours;

    @Column(name = "registered_amount", precision = 9, scale = 4)
    private BigDecimal registeredAmount; // done

    @Column(name = "budget_hours", precision = 7, scale = 4)
    private BigDecimal budgetHours;

    @Column(name = "budget_hours_with_no_availability_adjustment")
    private BigDecimal budgetHoursWithNoAvailabilityAdjustment;

    @Column(name = "contract_utilization", precision = 7, scale = 4)
    private BigDecimal contractUtilization;

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

    public EmployeeDataPerDay(LocalDate month, User user, double grossAvailableHours, double unavailableHours, double vacationHours, double sickHours, double maternityLeaveHours, double nonPaydLeaveHours, double paidLeaveHours, double registeredBillableHours, double helpedColleagueBillableHours, double registeredAmount, double contractUtilization, ConsultantType consultantType, StatusType statusType, int salary, boolean isTwBonusEligible) {
        this.lastUpdate = LocalDateTime.now();
        this.month = month;
        this.user = user;
        this.grossAvailableHours = BigDecimal.valueOf(grossAvailableHours);
        this.unavavailableHours = BigDecimal.valueOf(unavailableHours);
        this.vacationHours = BigDecimal.valueOf(vacationHours);
        this.sickHours = BigDecimal.valueOf(sickHours);
        this.maternityLeaveHours = BigDecimal.valueOf(maternityLeaveHours);
        this.nonPaydLeaveHours = BigDecimal.valueOf(nonPaydLeaveHours);
        this.paidLeaveHours = BigDecimal.valueOf(paidLeaveHours);
        this.registeredBillableHours = BigDecimal.valueOf(registeredBillableHours);
        this.helpedColleagueBillableHours = BigDecimal.valueOf(helpedColleagueBillableHours);
        this.registeredAmount = BigDecimal.valueOf(registeredAmount);
        this.contractUtilization = BigDecimal.valueOf(contractUtilization);
        this.budgetHours = BigDecimal.valueOf(0);
        this.budgetHoursWithNoAvailabilityAdjustment = BigDecimal.valueOf(0);
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
        return Math.max(grossAvailableHours.doubleValue() - unavavailableHours.doubleValue() - vacationHours.doubleValue() - sickHours.doubleValue()- maternityLeaveHours.doubleValue() - nonPaydLeaveHours.doubleValue() - paidLeaveHours.doubleValue(), 0.0);
    }

    @Transient
    @JsonIgnore
    public Double getActualUtilization() {
        // (5.4 / 7.4) * 100.0
        return ((registeredBillableHours.doubleValue() + helpedColleagueBillableHours.doubleValue()) / getNetAvailableHours()) / 100.0;
    }

    @Override
    public String toString() {
        return "AvailabilityPerDayDocument{" +
                "month=" + month +
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
package dk.trustworks.intranet.aggregateservices.model.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "employee_data_per_month")
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeDataPerMonth extends PanacheEntityBase {

    @Id
    @JsonIgnore
    private int id;

    private int year; // done

    private int month;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

    private String useruuid; // done

    @Column(name = "consultant_type")
    @Enumerated(EnumType.STRING)
    @JsonProperty("consultantType")
    private ConsultantType consultantType;

    @Column(name = "status_type")
    @Enumerated(EnumType.STRING)
    private StatusType status;

    @Column(name = "gross_available_hours", precision = 7, scale = 4)
    @JsonProperty("grossAvailableHours")
    // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    private BigDecimal grossAvailableHours; // done

    @Column(name = "unavailable_hours", precision = 7, scale = 4)
    @JsonProperty("unavailableHours")
    private BigDecimal unavailableHours; // F.eks. summen af fredage

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

    @JsonProperty("avgSalary")
    @Column(name = "avg_salary", precision = 14, scale = 4)
    private BigDecimal avgSalary;

    @Column(name = "is_tw_bonus_eligible")
    @JsonProperty("isTwBonusEligible")
    private boolean isTwBonusEligible;

    @JsonIgnore
    public LocalDate getDate() {
        return LocalDate.of(year, month, 1);
    }

    @Transient
    @JsonIgnore
    public Double getNetAvailableHours() {
        return Math.max(grossAvailableHours.doubleValue() - unavailableHours.doubleValue() - vacationHours.doubleValue() - sickHours.doubleValue()- maternityLeaveHours.doubleValue() - nonPaydLeaveHours.doubleValue() - paidLeaveHours.doubleValue(), 0.0);
    }

    @Override
    public String toString() {
        return "EmployeeDataPerMonth{" +
                "year=" + year +
                ", month=" + month +
                ", company=" + (company!=null?company.getName():"none") +
                ", useruuid='" + useruuid + "'" +
                ", consultantType=" + consultantType +
                ", status=" + status +
                ", grossAvailableHours=" + grossAvailableHours +
                ", unavailableHours=" + unavailableHours +
                ", vacationHours=" + vacationHours +
                ", sickHours=" + sickHours +
                ", maternityLeaveHours=" + maternityLeaveHours +
                ", nonPaydLeaveHours=" + nonPaydLeaveHours +
                ", paidLeaveHours=" + paidLeaveHours +
                ", avgSalary=" + avgSalary +
                ", isTwBonusEligible=" + isTwBonusEligible +
                '}';
    }
}

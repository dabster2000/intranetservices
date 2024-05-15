package dk.trustworks.intranet.aggregates.availability.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeAvailabilityPerMonth {

    private int year; // done
    private int month;
    private Company company;
    private String useruuid; // done
    @JsonProperty("consultantType")
    private ConsultantType consultantType;
    private StatusType status;
    @JsonProperty("grossAvailableHours")
    // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    private BigDecimal grossAvailableHours; // done
    @JsonProperty("unavailableHours")
    private BigDecimal unavailableHours; // F.eks. summen af fredage
    @JsonProperty("vacationHours")
    private BigDecimal vacationHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    @JsonProperty("sickHours")
    private BigDecimal sickHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    @JsonProperty("maternityLeaveHours")
    private BigDecimal maternityLeaveHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    @JsonProperty("nonPaydLeaveHours")
    private BigDecimal nonPaydLeaveHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    @JsonProperty("paidLeaveHours")
    private BigDecimal paidLeaveHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
    @JsonProperty("avgSalary")
    private BigDecimal avgSalary;
    @JsonProperty("isTwBonusEligible")
    private boolean isTwBonusEligible;
    @JsonIgnore
    public LocalDate getDate() {
        return LocalDate.of(year, month, 1);
    }

    @JsonIgnore
    public double getSalaryAwardingHours() {
        return Math.max(grossAvailableHours.doubleValue() - nonPaydLeaveHours.doubleValue(), 0.0);
    }

    @JsonIgnore
    public double getNetAvailableHours() {
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

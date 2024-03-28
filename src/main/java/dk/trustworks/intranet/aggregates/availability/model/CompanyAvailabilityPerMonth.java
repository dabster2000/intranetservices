package dk.trustworks.intranet.aggregates.availability.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Company;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
public class CompanyAvailabilityPerMonth {

    private int year; // done
    private int month;
    private Company company;
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
    private int avgSalary;
    @JsonIgnore
    @ToString.Include
    public LocalDate getDate() {
        return LocalDate.of(year, month, 1);
    }
    @JsonIgnore
    @ToString.Include
    public Double getNetAvailableHours() {
        return Math.max(grossAvailableHours.doubleValue() - unavailableHours.doubleValue() - vacationHours.doubleValue() - sickHours.doubleValue()- maternityLeaveHours.doubleValue() - nonPaydLeaveHours.doubleValue() - paidLeaveHours.doubleValue(), 0.0);
    }

}

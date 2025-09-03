package dk.trustworks.intranet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.TrustworksConfiguration;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityDocument {
    @JsonProperty("month")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate month;
    @JsonProperty("user")
    private User user;
    @JsonProperty("availableHours")
    private Double availableHours;
    @JsonProperty("vacation")
    private Double vacation;
    @JsonProperty("sickdays")
    private Double sickdays;
    @JsonProperty("maternityLeave")
    private Double maternityLeave;
    @JsonProperty("weeks")
    private Double weeks;
    @JsonProperty("weekdaysInPeriod")
    private Double weekdaysInPeriod;
    @JsonProperty("consultantType")
    private ConsultantType consultantType;
    @JsonProperty("statusType")
    private StatusType statusType;
    @JsonProperty("grossAvailableHours")
    private Double grossAvailableHours;
    @JsonProperty("netAvailableHours")
    private Double netAvailableHours;
    @JsonProperty("grossVacation")
    private Double grossVacation;
    @JsonProperty("netVacation")
    private Double netVacation;
    @JsonProperty("grossSickdays")
    private Double grossSickdays;
    @JsonProperty("netSickdays")
    private Double netSickdays;
    @JsonProperty("netMaternityLeave")
    private Double netMaternityLeave;

    public AvailabilityDocument(User user, LocalDate month, double availableHours, double vacation, double sickdays, double maternityLeave, ConsultantType consultantType, StatusType statusType) {
        this.user = user;
        this.month = month;
        this.availableHours = availableHours;
        this.vacation = vacation;
        this.sickdays = sickdays;
        this.maternityLeave = maternityLeave;
        this.consultantType = consultantType;
        this.statusType = statusType;
        weekdaysInPeriod = (double) DateUtils.getWeekdaysInPeriod(month, month.plusMonths(1));
        weeks = weekdaysInPeriod / 5.0;
    }

    /**
     * Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
     * @return Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer
     */
    public double getGrossAvailableHours() {
        return Math.max(availableHours * weeks, 0.0);
    }

    /**
     * Det antal timer, som konsulenten er tilgængelig, minus de to timer der bruges om fredagen samt eventuelt ferie og sygdom.
     * @return availability uden ferie, sygdom og fredage
     */
    public double getNetAvailableHours() {
        return Math.max((availableHours * weeks) - adjustForOffHours() - getNetVacation() - getNetSickdays() - getNetMaternityLeave(), 0.0); // F.eks. 2019-12-01: ((37 - 2) * 3,6) - (7,4 * 2 - 0.4) - (0 * 1)) = 111,2
    }

    private double adjustForOffHours() {
        int numberOfFridaysInPeriod = DateUtils.countWeekdayOccurances(DayOfWeek.FRIDAY, getMonth(), getMonth().plusMonths(1));
        int numberOfFridayHolidays = DateUtils.getVacationDayArray(getMonth().getYear()).stream()
                .filter(localDate -> localDate.getMonthValue() == getMonth().getMonthValue())
                .mapToInt(value -> (value.getDayOfWeek().getValue() != DayOfWeek.FRIDAY.getValue()) ? 0 : 1).sum();
        return (numberOfFridaysInPeriod - numberOfFridayHolidays) * TrustworksConfiguration.getWeekOffHours();
    }

    public double getGrossVacation() {
        return vacation;
    }

    public double getNetVacation() {
        return vacation;
    }

    public double getGrossSickdays() {
        return sickdays;
    }

    public double getNetSickdays() {
        return sickdays;
    }

    public double getNetMaternityLeave() {
        return maternityLeave;
    }
}

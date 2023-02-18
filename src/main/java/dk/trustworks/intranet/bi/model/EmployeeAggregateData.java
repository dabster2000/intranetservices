package dk.trustworks.intranet.bi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.TrustworksConfiguration;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.persistence.*;
import java.time.DayOfWeek;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "employee_data")
@NoArgsConstructor
@RequiredArgsConstructor
public class EmployeeAggregateData extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    @NonNull
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate month; // done

    @NonNull
    private String useruuid; // done

    @Column(name = "registered_hours")
    private double registeredHours; // done

    @Column(name = "helped_colleague_hours")
    private double helpedColleagueHours; // done

    @Column(name = "got_help_by_colleague_hours")
    private double gotHelpByColleagueHours; // done

    @Column(name = "registered_amount")
    private double registeredAmount; // done

    @Column(name = "contract_utilization")
    private double contractUtilization; // done

    @Column(name = "actual_utilization")
    private double actualUtilization; // done

    @Column(name = "net_available_hours")
    private double netAvailableHours; // done

    @Column(name = "gross_available_hours")
    private double grossAvailableHours; // done

    @Column(name = "available_hours")
    private double availableHours; // done

    @Column(name = "budget_amount")
    private double budgetAmount; // done

    @Column(name = "budget_hours")
    private double budgetHours; // done

    @Column(name = "budget_hours_no_adj")
    private double budgetHoursWithNoAvailabilityAdjustment; // done

    //@OneToMany(cascade = CascadeType.MERGE, fetch = FetchType.EAGER, orphanRemoval = true)
    //@JoinColumn(name = "employee_aggregate_id")
    //private List<BudgetDocument> budgetDocuments = new ArrayList<>();

    @Column(name = "salary")
    private double salary; // done

    @Column(name = "shared_expenses")
    private double sharedExpenses; // done

    @Column(name = "vacation")
    private double vacation; // done

    @Column(name = "sickdays")
    private double sickdays; // done

    @Column(name = "maternity_leave")
    private double maternityLeave; // done

    @Column(name = "weeks")
    private double weeks; // done

    @Column(name = "weekdays_in_period")
    private double weekdaysInPeriod; // done

    @Enumerated(EnumType.STRING)
    @Column(name = "consultant_type")
    private ConsultantType consultantType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_type")
    private StatusType statusType; //done

    //@ElementCollection(fetch = FetchType.EAGER)
    @Column(name = "team_member_of")
    private String teamMemberOf;

    //@ElementCollection(fetch = FetchType.EAGER)
    @Column(name = "team_leader_of")
    private String teamLeaderOf;

    //@ElementCollection(fetch = FetchType.EAGER)
    @Column(name = "team_sponsor_of")
    private String teamSponsorOf;

    //@ElementCollection(fetch = FetchType.EAGER)
    @Lob @Basic(fetch=FetchType.EAGER)
    @Column(name = "team_guest_of")
    private String teamGuestOf;

    public void addWorkDuration(double registeredHours) {
        this.registeredHours += registeredHours;
    }
    public void addHelpedColleagueHours(double duration) {
        this.helpedColleagueHours += duration;
    }
    public void addGotHelpByColleagueHours(double duration) {
        this.gotHelpByColleagueHours += duration;
    }
    public void addSickdays(double duration) {
        this.sickdays += duration;
    }
    public void addVacation(double duration) {
        this.vacation += duration;
    }
    public void addMaternity(double duration) {
        this.maternityLeave += duration;
    }
    public void addBudgetHours(double budgetHours) { this.budgetHours += budgetHours; }
    public void addBudgetHoursWithNoAvailabilityAdjustment(double budgetHoursWithNoAvailabilityAdjustment) { this.budgetHoursWithNoAvailabilityAdjustment += budgetHoursWithNoAvailabilityAdjustment; }
    public void addBudgetAmount(double budgetAmount) { this.budgetAmount += budgetAmount; }
    /*
    public void addBudgetDocument(BudgetDocument budgetDocument) {
        budgetDocuments.add(budgetDocument);
    }

     */


    public void addRegisteredAmount(double registeredAmount) {
        this.registeredAmount += registeredAmount;
    }

    @JsonProperty("weekdaysInPeriod")
    public double getWeekdaysInPeriod() {
        weekdaysInPeriod = DateUtils.getWeekdaysInPeriod(month, month.plusMonths(1));
        return weekdaysInPeriod;
    }

    @JsonProperty("weeks")
    public double getWeeks() {
        weeks = getWeekdaysInPeriod() / 5.0;
        return weeks;
    }

    /**
     * Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
     * @return Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer
     */
    @JsonProperty("grossAvailableHours")
    public double getGrossAvailableHours() {
        grossAvailableHours = Math.max(getAvailableHours() * getWeeks(), 0.0);
        return grossAvailableHours;
    }

    /**
     * Det antal timer, som konsulenten er tilgængelig, minus de to timer der bruges om fredagen samt eventuelt ferie og sygdom.
     * @return availability uden ferie, sygdom og fredage
     */
    @JsonProperty("netAvailableHours")
    public double getNetAvailableHours() {
        netAvailableHours = Math.max((getAvailableHours() * getWeeks()) - adjustForOffHours() - getVacation() - getSickdays() - getMaternityLeave(), 0.0); // F.eks. 2019-12-01: ((37 - 2) * 3,6) - (7,4 * 2 - 0.4) - (0 * 1)) = 111,2
        return netAvailableHours;
    }

    @JsonProperty("actualUtilization")
    public double getActualUtilization() {
        actualUtilization = getRegisteredHours() / getNetAvailableHours();
        return getNetAvailableHours()==0?0:actualUtilization;
    }

    @JsonProperty("contractUtilization")
    public double getContractUtilization() {
        contractUtilization = getBudgetHours() / getNetAvailableHours();
        return getNetAvailableHours()==0?0:contractUtilization;
    }

    private double adjustForOffHours() {
        int numberOfFridaysInPeriod = DateUtils.countWeekdayOccurances(DayOfWeek.FRIDAY, getMonth(), getMonth().plusMonths(1));
        int numberOfFridayHolidays = DateUtils.getVacationDayArray(getMonth().getYear()).stream()
                .filter(localDate -> localDate.getMonthValue() == getMonth().getMonthValue())
                .mapToInt(value -> (value.getDayOfWeek().getValue() != DayOfWeek.FRIDAY.getValue()) ? 0 : 1).sum();
        return (numberOfFridaysInPeriod - numberOfFridayHolidays) * TrustworksConfiguration.getWeekOffHours();
    }

}

package dk.trustworks.intranet.aggregates.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "employee_data")
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

    /**
     * Det antal timer, som konsulenten er tilgængelig, minus de to timer der bruges om fredagen samt eventuelt ferie og sygdom.
     * @return availability uden ferie, sygdom og fredage
     */
    @Column(name = "net_available_hours")
    private double netAvailableHours; // done

    /**
     * Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.
     * @return Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer
     */
    @Column(name = "gross_available_hours")
    private double grossAvailableHours; // done

    // Available hours during a week. A full time employement means 37 available hours.
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

    public EmployeeAggregateData() {

    }

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

    public void updateCalculatedData() {
        weekdaysInPeriod = DateUtils.getWeekdaysInPeriod(month, month.plusMonths(1));
        weeks = getWeekdaysInPeriod() / 5.0;
        grossAvailableHours = Math.max(getAvailableHours() * getWeeks(), 0.0);
        netAvailableHours = Math.max((getAvailableHours() * getWeeks()) - adjustForOffHours() - getVacation() - getSickdays() - getMaternityLeave(), 0.0); // F.eks. 2019-12-01: ((37 - 2) * 3,6) - (7,4 * 2 - 0.4) - (0 * 1)) = 111,2

        actualUtilization = getNetAvailableHours()!=0.0?getRegisteredHours() / getNetAvailableHours():0.0;
        contractUtilization = getNetAvailableHours()!=0.0?getBudgetHours() / getNetAvailableHours():0.0;
    }

    public void addRegisteredAmount(double registeredAmount) {
        this.registeredAmount += registeredAmount;
    }

    /*
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


    @JsonProperty("grossAvailableHours")
    public double getGrossAvailableHours() {
        grossAvailableHours = Math.max(getAvailableHours() * getWeeks(), 0.0);
        return grossAvailableHours;
    }

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

     */

    private double adjustForOffHours() {
        int numberOfFridaysInPeriod = DateUtils.countWeekdayOccurances(DayOfWeek.FRIDAY, getMonth(), getMonth().plusMonths(1));
        int numberOfFridayHolidays = DateUtils.getVacationDayArray(getMonth().getYear()).stream()
                .filter(localDate -> localDate.getMonthValue() == getMonth().getMonthValue())
                .mapToInt(value -> (value.getDayOfWeek().getValue() != DayOfWeek.FRIDAY.getValue()) ? 0 : 1).sum();
        return (numberOfFridaysInPeriod - numberOfFridayHolidays) * TrustworksConfiguration.getWeekOffHours();
    }

}

/*
data = EmployeeAggregateData(id=0, month=2021-07-01, useruuid=b01bfaa0-364b-4ef2-8286-86ea1ecc97b1, registeredHours=0.0, helpedColleagueHours=0.0, gotHelpByColleagueHours=0.0, registeredAmount=0.0, contractUtilization=NaN, actualUtilization=NaN, netAvailableHours=0.0, grossAvailableHours=0.0, availableHours=0.0, budgetAmount=0.0, budgetHours=0.0, budgetHoursWithNoAvailabilityAdjustment=0.0, salary=0.0, sharedExpenses=0.0, vacation=0.0, sickdays=0.0, maternityLeave=0.0, weeks=4.4, weekdaysInPeriod=22.0, consultantType=STAFF, statusType=TERMINATED, teamMemberOf=null, teamLeaderOf=null, teamSponsorOf=null, teamGuestOf=null)
2023-04-18 18:00:45,213 WARN  [org.mar.jdb.mes.ser.ErrorPacket] (vert.x-worker-thread-11) Error: 1054-42S22: Unknown column 'NaN' in 'field list'
2023-04-18 18:00:45,214 WARN  [org.hib.eng.jdb.spi.SqlExceptionHelper] (vert.x-worker-thread-11) SQL Error: 1054, SQLState: 42S22
2023-04-18 18:00:45,214 ERROR [org.hib.eng.jdb.spi.SqlExceptionHelper] (vert.x-worker-thread-11) (conn=756) Unknown column 'NaN' in 'field list'
2023-04-18 18:00:45,217 ERROR [io.qua.mut.run.MutinyInfrastructure] (vert.x-eventloop-thread-4) Mutiny had to drop the following exception: (RECIPIENT_FAILURE,8185) javax.persistence.PersistenceException: org.hibernate.exception.SQLGrammarException: could not execute statement
	at io.vertx.core.eventbus.Message.fail(Message.java:141)
	at io.quarkus.vertx.runtime.VertxRecorder$3$1$1.handle(VertxRecorder.java:122)
	at io.quarkus.vertx.runtime.VertxRecorder$3$1$1.handle(VertxRecorder.java:112)
	at io.vertx.core.impl.ContextBase.lambda$null$0(ContextBase.java:137)
	at io.vertx.core.impl.ContextInternal.dispatch(ContextInternal.java:264)
	at io.vertx.core.impl.ContextBase.lambda$executeBlocking$1(ContextBase.java:135)
	at org.jboss.threads.ContextHandler$1.runWith(ContextHandler.java:18)
	at org.jboss.threads.EnhancedQueueExecutor$Task.run(EnhancedQueueExecutor.java:2449)
	at org.jboss.threads.EnhancedQueueExecutor$ThreadBody.run(EnhancedQueueExecutor.java:1462)
	at org.jboss.threads.DelegatingRunnable.run(DelegatingRunnable.java:29)
	at org.jboss.threads.ThreadLocalResettingRunnable.run(ThreadLocalResettingRunnable.java:29)
	at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
	at java.base/java.lang.Thread.run(Thread.java:833)

 */

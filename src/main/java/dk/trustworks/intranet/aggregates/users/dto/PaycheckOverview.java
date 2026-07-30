package dk.trustworks.intranet.aggregates.users.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-employee paycheck composition: the last six locked payroll runs plus the open
 * "next paycheck" bucket. Mirrors the exact data HR reports to Danløn per pay month
 * (paid_out-stamped vacation/hourly/transportation/expense rows plus supplements,
 * lump sums and committed individual bonus), so the employee view matches payroll.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaycheckOverview {

    /** First day of the month whose payroll run has not been locked yet. */
    private LocalDate nextPayMonth;

    /** The six most recent locked pay months, newest first. */
    private List<PaycheckMonth> months = new ArrayList<>();

    /** The open bucket: everything that will be swept into the next payroll run. */
    private PaycheckMonth next;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaycheckMonth {
        /** Pay month (first day of month). */
        private LocalDate month;
        /** True for historical months whose payroll run is done. */
        private boolean locked;
        /** Latest paid_out stamp among the month's items; null when nothing was swept. */
        private LocalDateTime lockedAt;
        /** NORMAL or HOURLY; null when no salary record exists for the month. */
        private String salaryType;
        /** Monthly base salary (leave-adjusted when applicable); 0 for HOURLY employees. */
        private double baseSalary;
        /** True when non-pay leave reduced the base salary for this month. */
        private boolean salaryAdjusted;
        /** The unadjusted monthly salary when salaryAdjusted is true. */
        private double unadjustedSalary;
        private List<PaycheckLine> supplements = new ArrayList<>();
        private double supplementsTotal;
        private List<PaycheckLine> lumpSums = new ArrayList<>();
        private double lumpSumsTotal;
        /** Committed individual bonus (payouts + positive adjustments) for this pay month. */
        private double bonus;
        /** Vacation registrations reported to payroll in this pay month. */
        private List<PaycheckWorkItem> vacation = new ArrayList<>();
        private double vacationDays;
        /** Hourly-wage registrations paid in this pay month (HOURLY employees). */
        private List<PaycheckWorkItem> hourlyWork = new ArrayList<>();
        private double hourlyHours;
        private double hourlyRate;
        private double hourlyAmount;
        private List<PaycheckTransportItem> transportation = new ArrayList<>();
        private int transportationKm;
        /** Expenses reimbursed via this pay month (or, for the open bucket, ready to be). */
        private List<PaycheckExpenseItem> expenses = new ArrayList<>();
        private double expensesTotal;
        /** Open bucket only: unpaid expenses whose status keeps them out of the next sweep. */
        private List<PaycheckExpenseItem> expensesNotReady = new ArrayList<>();
        private double expensesNotReadyTotal;
        /** Open bucket only: unpaid vacation dated after the pay month — lands in a later run. */
        private List<PaycheckWorkItem> laterVacation = new ArrayList<>();
        /** Informational: sick-day registrations in the pay month's calendar month. */
        private List<PaycheckWorkItem> sickness = new ArrayList<>();
        private double sicknessDays;
        /** baseSalary + supplements + lump sums + bonus + hourly pay + expense reimbursements. */
        private double total;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaycheckLine {
        private String description;
        private double amount;
        private Boolean withPension;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaycheckWorkItem {
        /** The date the hours were registered for (not when they were paid). */
        private LocalDate date;
        private double hours;
        private LocalDateTime paidOut;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaycheckTransportItem {
        private LocalDate date;
        private String purpose;
        private String destination;
        private int kilometers;
        private LocalDateTime paidOut;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaycheckExpenseItem {
        private String uuid;
        private LocalDate date;
        private String description;
        private double amount;
        private String status;
        private LocalDateTime paidOut;
    }
}

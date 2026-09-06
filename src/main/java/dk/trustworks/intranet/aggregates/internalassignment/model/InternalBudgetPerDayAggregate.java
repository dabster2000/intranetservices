package dk.trustworks.intranet.aggregates.internalassignment.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One day of demand from one APPROVED internal assignment (V569, spec §4.3.3).
 *
 * <p>Same grain as {@code fact_budget_day} — (user, day, source) — but deliberately its own
 * table: no contract, no client, no rate. Internal hours are demand, never revenue.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "fact_internal_budget_day")
public class InternalBudgetPerDayAggregate extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "useruuid", length = 36, nullable = false)
    private String useruuid;

    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "month", nullable = false)
    private int month;

    @Column(name = "day", nullable = false)
    private int day;

    @Column(name = "internal_assignment_uuid", length = 36, nullable = false)
    private String internalAssignmentUuid;

    @Column(name = "companyuuid", length = 36)
    private String companyuuid;

    /** Hours after stacking against what the contract budgets left of the day's net availability. */
    @Column(name = "budget_hours", nullable = false)
    private double budgetHours;

    /** {@code hours_per_week / 5} before any clamping. */
    @Column(name = "budget_hours_unadjusted", nullable = false)
    private double budgetHoursUnadjusted;

    @Column(name = "strategic", nullable = false)
    private boolean strategic;

    @Column(name = "last_update", nullable = false)
    private LocalDateTime lastUpdate;

    public InternalBudgetPerDayAggregate(String useruuid, LocalDate documentDate, String internalAssignmentUuid,
                                         String companyuuid, double budgetHours, double budgetHoursUnadjusted,
                                         boolean strategic) {
        this.useruuid = useruuid;
        this.documentDate = documentDate;
        this.year = documentDate.getYear();
        this.month = documentDate.getMonthValue();
        this.day = documentDate.getDayOfMonth();
        this.internalAssignmentUuid = internalAssignmentUuid;
        this.companyuuid = companyuuid;
        this.budgetHours = budgetHours;
        this.budgetHoursUnadjusted = budgetHoursUnadjusted;
        this.strategic = strategic;
        this.lastUpdate = LocalDateTime.now();
    }

    public static long deleteForUserAndDay(String useruuid, LocalDate day) {
        return delete("useruuid = ?1 and documentDate = ?2", useruuid, day);
    }

    /** Rows in {@code [from, to)} for every user, the read-layer union's input. */
    public static List<InternalBudgetPerDayAggregate> findInPeriod(LocalDate from, LocalDate to) {
        return list("documentDate >= ?1 and documentDate < ?2", from, to);
    }

    public static List<InternalBudgetPerDayAggregate> findForUserInPeriod(String useruuid, LocalDate from, LocalDate to) {
        return list("useruuid = ?1 and documentDate >= ?2 and documentDate < ?3", useruuid, from, to);
    }
}

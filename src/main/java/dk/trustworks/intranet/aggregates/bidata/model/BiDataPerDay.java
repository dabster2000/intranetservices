package dk.trustworks.intranet.aggregates.bidata.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "bi_data_per_day", indexes = {
        @Index(name = "idx_availability_useruuid_month", columnList = "useruuid, document_date"),
        @Index(name = "idx_availability_month", columnList = "document_date"),
        @Index(name = "idx_year", columnList = "year"),
        @Index(name = "idx_consultant_type", columnList = "consultant_type"),
        @Index(name = "idx_status_type", columnList = "status_type")
})
public class BiDataPerDay extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    public Company company;

    @Column(name = "document_date")
    public LocalDate documentDate;

    @Column(name = "year")
    public Integer year;

    @Column(name = "month")
    public Integer month;

    @Column(name = "day")
    public Integer day;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    public User user;

    @Column(name = "gross_available_hours", precision = 7, scale = 4)
    public BigDecimal grossAvailableHours;

    @Column(name = "unavailable_hours", precision = 7, scale = 4)
    public BigDecimal unavailableHours;

    @Column(name = "vacation_hours", precision = 7, scale = 4)
    public BigDecimal vacationHours;

    @Column(name = "sick_hours", precision = 7, scale = 4)
    public BigDecimal sickHours;

    @Column(name = "maternity_leave_hours", precision = 7, scale = 4)
    public BigDecimal maternityLeaveHours;

    @Column(name = "non_payd_leave_hours", precision = 7, scale = 4)
    public BigDecimal nonPaydLeaveHours;

    @Column(name = "paid_leave_hours", precision = 7, scale = 4)
    public BigDecimal paidLeaveHours;

    @Column(name = "consultant_type", length = 50)
    public String consultantType;

    @Column(name = "status_type", length = 50)
    public String statusType;

    @Column(name = "contract_utilization", precision = 7, scale = 4)
    public BigDecimal contractUtilization;

    @Column(name = "actual_utilization", precision = 7, scale = 4)
    public BigDecimal actualUtilization;

    @Column(name = "registered_billable_hours", precision = 7, scale = 4)
    public BigDecimal registeredBillableHours;

    @Column(name = "helped_colleague_billable_hours", precision = 7, scale = 4)
    public BigDecimal helpedColleagueBillableHours;

    @Column(name = "registered_amount", precision = 9, scale = 2)
    public BigDecimal registeredAmount;

    @Column(name = "salary")
    public Integer salary;

    @Column(name = "last_update")
    public LocalDateTime lastUpdate;

    @Column(name = "is_tw_bonus_eligible")
    public Boolean isTwBonusEligible = false;

    public boolean isTwBonusEligible() {
        return isTwBonusEligible;
    }

    public Integer getSalary() {
        return salary==null?0:salary;
    }

    @Transient
    @JsonIgnore
    public Double getNetAvailableHours() {
        return grossAvailableHours!=null?Math.max(grossAvailableHours.doubleValue() - unavailableHours.doubleValue() - vacationHours.doubleValue() - sickHours.doubleValue()- maternityLeaveHours.doubleValue() - nonPaydLeaveHours.doubleValue() - paidLeaveHours.doubleValue(), 0.0):0.0;
    }
}

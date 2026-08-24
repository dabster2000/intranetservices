package dk.trustworks.intranet.vacationservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Temporal accrual rates (days per month per pool). Append-only, forward-only:
 * the service refuses rows with an effective_from at or before today, so
 * history can never be rewritten.
 */
@Data
@Entity
@Table(name = "vacation_policies")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class VacationPolicy extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "effective_from")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate effectiveFrom;

    @Column(name = "ferie_days_per_month")
    private double ferieDaysPerMonth;

    @Column(name = "feriefridage_days_per_month")
    private double feriefridageDaysPerMonth;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    public static List<VacationPolicy> listOrdered() {
        return list("ORDER BY effectiveFrom");
    }
}

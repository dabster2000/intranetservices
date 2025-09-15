package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

@Getter @Setter
@Entity
@Table(name = "invoice_bonus_eligibility_group")
@Schema(name = "BonusEligibilityGroup", description = "Group of bonus eligibility entries for a financial year (July 1 - June 30). Brugere kan være med i forskellige grupper på tværs af år, men kun én eligibility per finansår (håndhæves i BonusEligibility).")
public class BonusEligibilityGroup extends PanacheEntityBase {

    @Id
    @Schema(description = "Group UUID", example = "44444444-4444-4444-4444-444444444444", readOnly = true)
    public String uuid;

    @Column(name = "name", nullable = false, length = 255)
    @Schema(description = "Group name", example = "FY2025")
    public String name;

    @Column(name = "financial_year", nullable = false)
    @Schema(description = "Financial year starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)", example = "2025")
    public int financialYear;

    @Transient
    @Schema(description = "Computed financial year start date (inclusive)", example = "2025-07-01", readOnly = true)
    public LocalDate getFinancialYearStart() {
        return LocalDate.of(financialYear, 7, 1);
    }

    @Transient
    @Schema(description = "Computed financial year end date (inclusive)", example = "2026-06-30", readOnly = true)
    public LocalDate getFinancialYearEnd() {
        return LocalDate.of(financialYear + 1, 6, 30);
    }

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
    }
}

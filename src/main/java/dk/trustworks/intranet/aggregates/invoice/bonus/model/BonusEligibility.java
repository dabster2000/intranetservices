package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;

import java.util.UUID;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Getter @Setter
@Entity
@Table(name = "invoice_bonus_eligibility")
@Schema(
        name = "BonusEligibility",
        description = "Whitelist over brugere, der må self-assign bonus på fakturaer. Én række per bruger per finansår (FY). Brugere kan have flere rækker på tværs af år, men kun én per FY. Ved oprettelse/ændring skal financialYear matche gruppens financialYear. canSelfAssign styrer kun selv-tilføjelse; administrator-tilføjelser er ikke begrænset af dette flag."
)
public class BonusEligibility extends PanacheEntityBase {

    @Id
    @Schema(description = "Eligibility UUID", example = "55555555-5555-5555-5555-555555555555", readOnly = true)
    public String uuid;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_uuid", referencedColumnName = "uuid", nullable = true)
    @Schema(description = "Reference to the BonusEligibilityGroup for the same financialYear. Must match financialYear.")
    public BonusEligibilityGroup group;

    @Column(name="useruuid", nullable=false, length=36)
    @Schema(description = "Bruger UUID", example = "11111111-1111-1111-1111-111111111111")
    public String useruuid;

    @Column(name="financial_year", nullable=false)
    @Schema(description = "Financial year starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)", example = "2025")
    public int financialYear;

    @Column(name="can_self_assign", nullable=false)
    @Schema(description = "Må brugeren selvtilføje bonus?", example = "true")
    public boolean canSelfAssign;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        if (group != null && financialYear != group.getFinancialYear()) {
            throw new IllegalStateException("financialYear must match group's financialYear");
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (group != null && financialYear != group.getFinancialYear()) {
            throw new IllegalStateException("financialYear must match group's financialYear");
        }
    }

    @Override
    public String toString() {
        return "BonusEligibility{" +
                "uuid='" + uuid + '\'' +
                ", group=" + group +
                ", useruuid='" + useruuid + '\'' +
                ", financialYear=" + financialYear +
                ", canSelfAssign=" + canSelfAssign +
                '}';
    }
}

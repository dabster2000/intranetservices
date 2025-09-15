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
        description = "Whitelist over brugere, der må self-assign bonus på fakturaer."
)
public class BonusEligibility extends PanacheEntityBase {

    @Id
    @Schema(description = "Eligibility UUID", example = "55555555-5555-5555-5555-555555555555", readOnly = true)
    public String uuid;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_uuid", referencedColumnName = "uuid", nullable = true)
    public BonusEligibilityGroup group;

    @Column(name="useruuid", nullable=false, unique=true, length=36)
    @Schema(description = "Bruger UUID", example = "11111111-1111-1111-1111-111111111111")
    public String useruuid;

    @Column(name="can_self_assign", nullable=false)
    @Schema(description = "Må brugeren selvtilføje bonus?", example = "true")
    public boolean canSelfAssign;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
        return "BonusEligibility{" +
                "uuid='" + uuid + '\'' +
                ", group=" + group +
                ", useruuid='" + useruuid + '\'' +
                ", canSelfAssign=" + canSelfAssign +
                '}';
    }
}

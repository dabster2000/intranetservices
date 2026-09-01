package dk.trustworks.intranet.agreementservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Closed, HR-manageable vocabulary the agreement registry groups by
 * (template-clauses spec §4.6). Seeded by V547 from the recurring
 * patterns in the existing tillæg corpus; {@code INDIVIDUEL} is the
 * fallback bucket for free-text aftaler and clauses without a mapped
 * type.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "agreement_types")
public class AgreementType extends PanacheEntityBase {

    /** Fallback bucket — seeded by V547, relied on by the recorder. */
    public static final String INDIVIDUEL = "INDIVIDUEL";

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "type_key", length = 50)
    private String typeKey;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Whether {@code valid_to} is expected; drives expiry alerts. */
    @Column(name = "time_limited", nullable = false)
    private boolean timeLimited;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public static List<AgreementType> findAllOrdered() {
        return list("ORDER BY displayOrder, typeKey");
    }
}

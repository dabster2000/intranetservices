package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A declarative, per-employee, time-boxed individual bonus rule.
 * <p>
 * The declarative {@code spec} (basis, tier table, pro-rating, schedule, replaces) is stored as a
 * JSON STRING — deliberately NOT a native JSON column, which crashes Quarkus boot here (global
 * JavaTimeObjectMapperCustomizer). It is (de)serialised by {@code IndividualBonusSpecMapper}.
 * <p>
 * Audit fields are populated by {@link AuditEntityListener} from the X-Requested-By header, mirroring
 * {@code SalaryLumpSum}.
 */
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "individual_bonus_rule")
@EntityListeners(AuditEntityListener.class)
public class IndividualBonusRule extends PanacheEntityBase implements Auditable {

    @Id
    @Size(max = 36)
    @EqualsAndHashCode.Include
    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    @NotNull
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    @NotNull
    @Size(max = 255)
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** The declarative spec as JSON text — NOT a native JSON column (see class doc). */
    @NotNull
    @Column(name = "spec", nullable = false)
    private String spec;

    @Size(max = 50)
    @Column(name = "replaces", length = 50)
    private String replaces;

    @NotNull
    @ColumnDefault("1")
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** Optimistic concurrency token. Existing rows start at revision zero. */
    @Version
    @NotNull
    @ColumnDefault("0")
    @Column(name = "revision", nullable = false)
    private Long revision = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public static List<IndividualBonusRule> findByUser(String userUuid) {
        return find("userUuid", userUuid).list();
    }

    public static List<IndividualBonusRule> findActiveByUser(String userUuid) {
        return find("userUuid = ?1 and active = true", userUuid).list();
    }
}

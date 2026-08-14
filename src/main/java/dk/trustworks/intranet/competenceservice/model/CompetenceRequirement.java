package dk.trustworks.intranet.competenceservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A compliance requirement (Danish: <em>krav</em>) and its targeting.
 *
 * <p>The three {@code target*} columns are byte-compatible with the questionnaire
 * columns — JSON array of uuids, NULL or blank meaning absent — so the storage concept
 * stays singular across the codebase. <strong>The combination rule differs and that
 * difference is load-bearing.</strong> See
 * {@code CompetenceAudienceMatcher}: the questionnaire reader chains its filters so
 * practice AND team must both match, which applied here would exclude every technology
 * team lead, because team leads carry {@code practice_uuid = NULL}. Do not "correct"
 * one reader to match the other.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "competence_requirement")
@EntityListeners(AuditEntityListener.class)
public class CompetenceRequirement extends PanacheEntityBase implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    /** Stable slug from the content export ({@code sikker-kode}). The import/export key. */
    @Column(name = "comp_id")
    private String compId;

    /** The SKI reference, e.g. {@code 7.b.3}. Not unique — a future krav may share one. */
    private String kref;

    private String name;

    private String description;

    /** JSON array of {@code practice.uuid}. */
    @Column(name = "target_practice_uuids", columnDefinition = "TEXT")
    private String targetPracticeUuids;

    /** JSON array of {@code team.uuid}. */
    @Column(name = "target_teams", columnDefinition = "TEXT")
    private String targetTeams;

    /**
     * JSON array of {@code user.uuid} — named individuals.
     *
     * <p>The escape hatch for people no practice or team rule reaches: a CTO sitting on
     * the Teamleads team, a contractor, someone on loan. Without it the only way to
     * include them is to widen the practice target and over-include everyone else.
     */
    @Column(name = "target_useruuids", columnDefinition = "TEXT")
    private String targetUseruuids;

    /** NULL = use the global {@code competence.cadence-days}. */
    @Column(name = "cadence_days_override")
    private Integer cadenceDaysOverride;

    @Column(name = "sort_order")
    private int sortOrder;

    /** Soft retire: inactive requirements leave the matrix but keep their history. */
    private boolean active = true;

    @Column(name = "created_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "updated_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public static CompetenceRequirement findByUuid(String uuid) {
        return find("uuid", uuid).firstResult();
    }

    public static CompetenceRequirement findByCompId(String compId) {
        return find("compId", compId).firstResult();
    }

    public static List<CompetenceRequirement> listActive() {
        return list("active = true order by sortOrder, name");
    }

    public static List<CompetenceRequirement> listAllOrdered() {
        return list("order by sortOrder, name");
    }
}

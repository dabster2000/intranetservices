package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentDemandRag;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * A recruitment position / opening (ATS spec §4.1) — the thing candidates
 * apply to. One of the two P2 aggregates (with the circle membership rows
 * that gate partner-track visibility).
 * <p>
 * Practice references follow the registry idiom (Practice Part 2):
 * {@link #practiceUuid} is the persisted key (real FK to the
 * {@code practice(uuid)} UNIQUE key) and {@link #practiceCode} is DERIVED via
 * the {@code @Formula} registry lookup, exactly like {@code Team.practiceCode}
 * — recruitment never stores practice codes.
 * <p>
 * {@code stage_set} and {@code scorecard_template} are per-position JSON
 * snapshots, copied from the track defaults at create time
 * ({@code RecruitmentPositionDefaults}) so later default changes never
 * rewrite in-flight positions.
 * <p>
 * State changes are only made through {@code RecruitmentPositionService},
 * which pairs every mutation with its {@code POSITION_*} event on the
 * recruitment event stream.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_positions")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentPosition extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** Immutable after create — see {@link RecruitmentHiringTrack}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hiring_track", length = 20, nullable = false, updatable = false)
    private RecruitmentHiringTrack hiringTrack;

    /**
     * The position's practice identity — FK to {@code practice(uuid)}.
     * Required for {@code PRACTICE_TEAM}, optional for {@code PARTNER} /
     * {@code STAFF_ROLE}. A grouping/reporting attribute, never a constraint
     * on team choice (spec §4.1).
     */
    @Column(name = "practice_uuid", length = 36)
    private String practiceUuid;

    /**
     * Practice code, DERIVED from {@link #practiceUuid} via the registry —
     * same idiom as {@code Team.practiceCode}. Carried on the wire next to
     * the uuid; never persisted here.
     */
    @Formula("(select prc.code from practice prc where prc.uuid = practice_uuid)")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String practiceCode;

    /**
     * Registry name of the practice, derived like {@link #practiceCode} so
     * the UI can render group headers without a second lookup. Also answers
     * "does the practice still exist / is it active" via
     * {@link #practiceActive}.
     */
    @Formula("(select prc.name from practice prc where prc.uuid = practice_uuid)")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String practiceName;

    /**
     * Whether the referenced practice is currently active in the registry.
     * The registry is runtime-mutable: a position on a since-deactivated
     * practice keeps working end-to-end and renders an "inactive practice"
     * badge (spec §4.1 design notes).
     */
    @Formula("(select prc.active from practice prc where prc.uuid = practice_uuid)")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean practiceActive;

    /** Soft FK to {@code team.uuid}. NULL = team decided at offer. */
    @Column(name = "team_uuid", length = 36)
    private String teamUuid;

    /** Soft FK to {@code users.uuid}. Required for {@code STAFF_ROLE}. */
    @Column(name = "hiring_owner_uuid", length = 36)
    private String hiringOwnerUuid;

    /** URL slug for the public application form (P5). NULL = no public form. */
    @Column(name = "public_slug", length = 80)
    private String publicSlug;

    /** Ordered stage codes — an ordered subset of {@code RecruitmentStage}. */
    @Convert(converter = StageSetConverter.class)
    @Column(name = "stage_set", columnDefinition = "JSON")
    private List<String> stageSet;

    /** Scorecard attributes for this position's interviews (P11). */
    @Convert(converter = ScorecardTemplateConverter.class)
    @Column(name = "scorecard_template", columnDefinition = "JSON")
    private List<ScorecardAttribute> scorecardTemplate;

    @Enumerated(EnumType.STRING)
    @Column(name = "demand_rag", length = 10, nullable = false)
    private RecruitmentDemandRag demandRag = RecruitmentDemandRag.GREEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private RecruitmentPositionStatus status = RecruitmentPositionStatus.OPEN;

    @Column(name = "opened_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime closedAt;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (openedAt == null) {
            openedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (status == null) {
            status = RecruitmentPositionStatus.OPEN;
        }
        if (demandRag == null) {
            demandRag = RecruitmentDemandRag.GREEN;
        }
    }
}

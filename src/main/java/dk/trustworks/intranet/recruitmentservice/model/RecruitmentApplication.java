package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An application — one candidate's pipeline run on one position (ATS spec
 * §4.1/§4.2). The stage machine lives here as domain methods; the
 * application service composes them with authorization, events and the
 * cross-aggregate effects (retention bookkeeping, pool moves, consents).
 * <p>
 * Two orthogonal columns describe the lifecycle:
 * <ul>
 *   <li>{@link #stage} — where the application is (or was) in the
 *       position's {@code stage_set}. Moves are validated against that
 *       ordered subset, never a global list.</li>
 *   <li>{@link #terminal} — why it left the pipeline ({@code NULL} =
 *       still open). Terminal is the ONLY removal mechanism; rows are
 *       never deleted.</li>
 * </ul>
 * {@code HIRED} is deliberately unreachable through
 * {@link #moveToStage(RecruitmentStage, List)} — it arrives only via the
 * signing-completion → conversion bridge (P10).
 * <p>
 * State changes are only made through {@code RecruitmentApplicationService},
 * which pairs every mutation with its {@code APPLICATION_*} event on the
 * recruitment event stream.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_applications")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentApplication extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_candidates.uuid}. */
    @Column(name = "candidate_uuid", length = 36, nullable = false, updatable = false)
    private String candidateUuid;

    /**
     * FK to {@code recruitment_positions.uuid}. Mutable through exactly one
     * path — {@link #moveToPosition(String, List)}, the re-filing command
     * that corrects a wrong req without splitting the pipeline run. Every
     * other caller treats it as write-once.
     */
    @Column(name = "position_uuid", length = 36, nullable = false)
    private String positionUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 20, nullable = false)
    private RecruitmentStage stage = RecruitmentStage.SCREENING;

    /** {@code NULL} while the application is open. */
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal", length = 20)
    private RecruitmentApplicationTerminal terminal;

    /** Mandatory when {@link #terminal} is {@code REJECTED}; coded for reporting. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason_code", length = 40)
    private RecruitmentRejectionReason rejectionReasonCode;

    /**
     * Soft FK to {@code team.uuid} — the offer-stage team decision on
     * practice-level positions. Any team is valid: teams are temporally
     * practice-assigned and may be practice-less (spec §4.1).
     */
    @Column(name = "assigned_team_uuid", length = 36)
    private String assignedTeamUuid;

    /** Airtable <em>Ansættelsesdato</em> — set at OFFER; feeds demand planning. */
    @Column(name = "expected_start_date")
    private LocalDate expectedStartDate;

    /** UTC. Updated on every stage move — idle detection. */
    @Column(name = "stage_entered_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime stageEnteredAt;

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
        if (stage == null) {
            stage = RecruitmentStage.SCREENING;
        }
        if (stageEnteredAt == null) {
            stageEnteredAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    /** @return true iff this application has left the pipeline. */
    public boolean isTerminal() {
        return terminal != null;
    }

    // ---- Domain methods (rich aggregate, not anemic) -------------------------

    /**
     * The direction of a stage move, derived from the position's ordered
     * stage set. {@code BACK} moves are legal but flagged — the event
     * carries {@code direction=BACK} so the timeline never shows a silent
     * rewind (spec §4.2 invariant 1).
     */
    public enum MoveDirection {
        FORWARD,
        BACK
    }

    /** Result of a {@link #moveToStage} call — what the event needs to record. */
    public record StageMove(RecruitmentStage from, RecruitmentStage to,
                            MoveDirection direction, boolean skippedStages) {
    }

    /**
     * Move this application to {@code target}, validated against the
     * position's ordered {@code stageSet} (spec §4.2 invariants 1 and 3).
     * Updates {@link #stage} and {@link #stageEnteredAt}; the caller
     * (application service) decides whether a forward <em>skip</em> is
     * allowed for the acting user and appends the event.
     *
     * @param target   the requested stage
     * @param stageSet the position's ordered stage codes
     * @return the move descriptor (from/to/direction/skipped)
     * @throws BusinessRuleViolation on any illegal transition: terminal
     *         application, HIRED target, stage outside the position's set,
     *         or a no-op move
     */
    public StageMove moveToStage(RecruitmentStage target, List<String> stageSet) {
        Objects.requireNonNull(target, "target stage must not be null");
        Objects.requireNonNull(stageSet, "stageSet must not be null");
        guardOpen("move");
        if (target == RecruitmentStage.HIRED) {
            throw new BusinessRuleViolation(
                    "HIRED is only reachable through signing completion and conversion — never a stage move");
        }
        int fromIndex = stageSet.indexOf(stage.name());
        int toIndex = stageSet.indexOf(target.name());
        if (toIndex < 0) {
            throw new BusinessRuleViolation(
                    "Stage %s is not part of this position's pipeline (%s)"
                            .formatted(target, String.join(" → ", stageSet)));
        }
        if (fromIndex < 0) {
            // Defensive: the current stage should always be in the set (the
            // set is immutable-in-practice after applications exist).
            throw new BusinessRuleViolation(
                    "Application %s is in stage %s which is not part of the position's pipeline"
                            .formatted(uuid, stage));
        }
        if (toIndex == fromIndex) {
            throw new BusinessRuleViolation(
                    "Application %s is already in stage %s".formatted(uuid, target));
        }

        RecruitmentStage from = this.stage;
        MoveDirection direction = toIndex > fromIndex ? MoveDirection.FORWARD : MoveDirection.BACK;
        boolean skipped = Math.abs(toIndex - fromIndex) > 1;

        this.stage = target;
        this.stageEnteredAt = LocalDateTime.now(ZoneOffset.UTC);
        return new StageMove(from, target, direction, skipped);
    }

    /** Result of a {@link #moveToPosition} call — what the event needs to record. */
    public record PositionMove(String fromPositionUuid, String toPositionUuid,
                               RecruitmentStage fromStage, RecruitmentStage toStage,
                               boolean stageClamped) {
    }

    /**
     * Re-file this application onto another position — the SAME pipeline
     * run continues under a new req. Everything that hangs off the
     * application uuid (interviews, scorecards, record checks, form
     * answers, the Slack card, the timeline) follows automatically; no row
     * is closed and none is created. This is the ONLY sanctioned writer of
     * {@link #positionUuid} after creation.
     * <p>
     * The landing stage is preserved whenever the target position's stage
     * set contains it — a candidate mid-way through Interview 1 does not
     * restart because the req was wrong. When the target pipeline is
     * shorter and has no such stage, the stage is <em>clamped backwards</em>
     * to the latest target stage at or before the current one (never
     * forward — a move must not silently advance anyone), falling back to
     * the target's first stage. A clamp restarts
     * {@link #stageEnteredAt}, because the application genuinely entered a
     * different stage; an unchanged stage keeps its original timestamp, so
     * idle detection still measures the real wait.
     *
     * @param targetPositionUuid the destination position
     * @param targetStageSet     the destination position's ordered stage codes
     * @return the move descriptor (positions/stages/clamped)
     * @throws BusinessRuleViolation terminal application, {@code HIRED}
     *         application (a finished hire is never re-filed), or a no-op
     *         move to the position it is already on
     */
    public PositionMove moveToPosition(String targetPositionUuid, List<String> targetStageSet) {
        Objects.requireNonNull(targetPositionUuid, "targetPositionUuid must not be null");
        Objects.requireNonNull(targetStageSet, "targetStageSet must not be null");
        guardOpen("move to another position");
        if (stage == RecruitmentStage.HIRED) {
            throw new BusinessRuleViolation(
                    ("Cannot move application %s to another position: the candidate is already hired — "
                            + "a completed hire stays on the position it was made against")
                            .formatted(uuid));
        }
        if (targetPositionUuid.equals(positionUuid)) {
            throw new BusinessRuleViolation(
                    "Application %s is already on position %s".formatted(uuid, targetPositionUuid));
        }

        RecruitmentStage fromStage = this.stage;
        RecruitmentStage landing = landingStageIn(targetStageSet, fromStage);
        boolean clamped = landing != fromStage;

        String fromPosition = this.positionUuid;
        this.positionUuid = targetPositionUuid;
        this.stage = landing;
        if (clamped) {
            this.stageEnteredAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        return new PositionMove(fromPosition, targetPositionUuid, fromStage, landing, clamped);
    }

    /**
     * Where {@code current} lands in {@code targetStageSet}: itself when the
     * set contains it, otherwise the latest set entry at or before it in the
     * canonical {@link RecruitmentStage} order (never forward), otherwise
     * the set's first stage. {@code HIRED} is never a landing stage — it is
     * reachable only through conversion.
     */
    private static RecruitmentStage landingStageIn(List<String> targetStageSet,
                                                   RecruitmentStage current) {
        List<RecruitmentStage> stages = targetStageSet.stream()
                .map(RecruitmentStage::valueOf)
                .filter(s -> s != RecruitmentStage.HIRED)
                .toList();
        if (stages.isEmpty()) {
            return RecruitmentStage.SCREENING;
        }
        if (stages.contains(current)) {
            return current;
        }
        return stages.stream()
                .filter(s -> s.ordinal() < current.ordinal())
                .reduce((first, second) -> second)   // the latest one at or before `current`
                .orElse(stages.get(0));
    }

    /**
     * Reject this application (Trustworks said no). The reason code is
     * mandatory — reporting aggregates on it (spec §4.2 invariant 4).
     *
     * @throws BusinessRuleViolation if the application is already terminal
     */
    public void reject(RecruitmentRejectionReason reasonCode) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        guardOpen("reject");
        this.terminal = RecruitmentApplicationTerminal.REJECTED;
        this.rejectionReasonCode = reasonCode;
    }

    /**
     * The candidate backed out of this application.
     *
     * @throws BusinessRuleViolation if the application is already terminal
     */
    public void withdraw() {
        guardOpen("withdraw");
        this.terminal = RecruitmentApplicationTerminal.WITHDRAWN;
    }

    /**
     * Close this application as a silver-medalist outcome. The caller pairs
     * this with the candidate pool move and the consent request
     * (spec §4.2 terminal note).
     *
     * @throws BusinessRuleViolation if the application is already terminal
     */
    public void returnToPool() {
        guardOpen("return to pool");
        this.terminal = RecruitmentApplicationTerminal.RETURNED_TO_POOL;
    }

    /**
     * Bridge-only transition into the {@code HIRED} stage — reached
     * exclusively through the signing-completion → conversion bridge (P10),
     * never through {@link #moveToStage(RecruitmentStage, List)}, which
     * keeps hard-rejecting {@code HIRED} (spec §4.2 invariant 3).
     * <p>
     * <b>Precondition / call-site contract:</b> the ONLY legitimate caller
     * is {@code RecruitmentOfferBridge.onCandidateConverted} (the
     * conversion path). This method must NEVER be called from a resource
     * or any other API-reachable path — {@code HIRED} is unreachable via
     * the stage API by design, and adding a second caller would break
     * that invariant.
     * <p>
     * {@code HIRED} is a STAGE, not a terminal: {@link #terminal} stays
     * {@code NULL} — the enum has no HIRED value and the DB check
     * {@code chk_ra_terminal_enum} would refuse one. Queries that mean
     * "still in play" must therefore exclude {@code stage = HIRED}
     * explicitly.
     *
     * @throws BusinessRuleViolation if the application is terminal or not
     *         currently at {@code OFFER} — conversion of a candidate whose
     *         open application is not at Offer is a state error
     */
    public void markHired() {
        guardOpen("mark as hired");
        if (stage != RecruitmentStage.OFFER) {
            throw new BusinessRuleViolation(
                    ("Cannot mark application %s as hired: it is in stage %s, not OFFER — "
                            + "hiring happens from the Offer stage via conversion")
                            .formatted(uuid, stage));
        }
        this.stage = RecruitmentStage.HIRED;
        this.stageEnteredAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Record the offer-stage team decision. Any team is valid — the
     * position's practice is a grouping attribute, never a constraint on
     * team choice (spec §4.1).
     *
     * @throws BusinessRuleViolation if the application is already terminal
     */
    public void assignTeam(String teamUuid) {
        Objects.requireNonNull(teamUuid, "teamUuid must not be null");
        guardOpen("assign a team to");
        this.assignedTeamUuid = teamUuid;
    }

    private void guardOpen(String operation) {
        if (isTerminal()) {
            throw new BusinessRuleViolation(
                    "Cannot %s application %s: it is closed (%s)"
                            .formatted(operation, uuid, terminal));
        }
    }
}

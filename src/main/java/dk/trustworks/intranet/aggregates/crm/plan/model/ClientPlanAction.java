package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionCadence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionPriority;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One executable next step on a plan (CRM spec §3.3).
 *
 * <p><b>An action has a due date OR a cadence.</b> One with neither is incomplete — rule 2
 * of the account-plan data model — and {@code AccountPlanService} refuses it. "Keep in
 * touch with the programme office" with no date and no rhythm is a wish, and a plan full
 * of wishes is what this model exists to prevent. The rule lives in the service rather
 * than as a DB CHECK because CHECK constraints in this schema have a history of existing
 * in one environment and not another (chk_consultant_positive_rate).
 *
 * <p>{@link #fromSuggestionId} records that the action was accepted from something Intra
 * proposed, so the same suggestion is not offered again the next time the tab is opened.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan_action")
public class ClientPlanAction extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "title", length = 300, nullable = false)
    private String title;

    /** How it will be done — one line, optional. */
    @Column(name = "how", length = 500)
    private String how;

    @Column(name = "owner_uuid", length = 36, nullable = false)
    private String ownerUuid;

    /** Null when the action recurs instead. Exactly one of this and {@link #cadence} is set. */
    @Column(name = "due")
    private LocalDate due;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", length = 15)
    private ActionCadence cadence;

    /** Next occurrence of a recurring action. */
    @Column(name = "next_due")
    private LocalDate nextDue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private ActionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 10, nullable = false)
    private ActionPriority priority;

    @Column(name = "objective_uuid", length = 36)
    private String objectiveUuid;

    /** The signal this action answers, when it came from one. */
    @Column(name = "signal_uuid", length = 36)
    private String signalUuid;

    @Column(name = "stakeholder_uuid", length = 36)
    private String stakeholderUuid;

    /** What happened. Written when the action is closed, not before. */
    @Column(name = "result", length = 1000)
    private String result;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "from_suggestion_id", length = 120)
    private String fromSuggestionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}

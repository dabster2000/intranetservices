package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanStatus;
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
 * The account plan a person owns (CRM spec §3.3, account-plan data model of 2026-09-12).
 *
 * <p>This row is the plan's identity and health. Its content lives in the child tables:
 * four sentences, at most four objectives, the open actions, the people on the plan and
 * the review log.
 *
 * <p><b>{@link #healthRag} is nullable and null means NOT ASSESSED</b> — rule 8 of the
 * data model. A blank must never render as green, and a green with no reason in
 * {@link #healthWhy} is stored as null by {@code AccountPlanService} for exactly that
 * reason: a colour nobody can justify is not an assessment.
 *
 * <p>Nothing derived is stored here. Plan freshness, which required fields are missing,
 * the live value behind a RATE objective, the suggested actions, the contradictions and
 * "what changed since the last review" are all computed in the frontend's
 * {@code planDerivations.ts} from these records plus the page's real contracts and leads.
 * Storing a derived value is how it goes stale.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan")
public class ClientPlan extends PanacheEntityBase {

    @Id
    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private PlanStatus status;

    /** Bumped when a review closes. The snapshot records which version it froze. */
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "next_review")
    private LocalDate nextReview;

    /** Null is "not assessed" — a real state, never a blank green. */
    @Enumerated(EnumType.STRING)
    @Column(name = "health_rag", length = 10)
    private PlanRag healthRag;

    @Column(name = "health_why", length = 500)
    private String healthWhy;

    @Column(name = "health_set_by", length = 36)
    private String healthSetBy;

    @Column(name = "health_set_at")
    private LocalDateTime healthSetAt;

    /** When anyone last confirmed or saved the plan — what "Still true" writes. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}

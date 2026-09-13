package dk.trustworks.intranet.aggregates.crm.plan.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One closed review of the plan (CRM spec §3.3, rule 9).
 *
 * <p>Closing a review bumps {@link ClientPlan#getVersion()} and writes a
 * {@link ClientPlanSnapshot}, so the numbers and colours as of that meeting survive every
 * later edit. Without it "what changed since the last review" would have nothing to
 * compare against and the plan would read as though it had always said what it says now.
 *
 * <p>Attendees and decisions live in {@code client_plan_review_attendee} and
 * {@code client_plan_review_decision}. Those are pure link tables with no behaviour, so
 * they have no entity — {@code AccountPlanService} reads and writes them with bound native
 * queries rather than carrying two more classes for two columns each.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan_review")
public class ClientPlanReview extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "review_date", nullable = false)
    private LocalDate reviewDate;

    /** The plan version this review closed. */
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "outcome", length = 1000, nullable = false)
    private String outcome;

    @Column(name = "next_review")
    private LocalDate nextReview;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}

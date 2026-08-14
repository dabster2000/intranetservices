package dk.trustworks.intranet.competenceservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The append-only approval ledger.
 *
 * <p>The attempt row carries no approval state, which is exactly what lets it stay
 * frozen. The effective state of an attempt is the latest decision here, or PENDING when
 * there is none. Correcting a decision means appending a REVOKED row — the UPDATE and
 * DELETE triggers refuse anything else.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "competence_attempt_decision")
public class CompetenceAttemptDecision extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "attempt_uuid")
    private String attemptUuid;

    @Enumerated(EnumType.STRING)
    private DecisionType decision;

    /** The leader who acted. Never the subject — self-approval is refused. */
    @Column(name = "actor_uuid")
    private String actorUuid;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Required for REVOKED, optional for APPROVED. */
    private String note;

    /** The deciding row for one attempt, or {@code null} when still PENDING. */
    public static CompetenceAttemptDecision findLatest(String attemptUuid) {
        return find("attemptUuid = ?1 order by decidedAt desc", attemptUuid).firstResult();
    }

    /** Every decision for a set of attempts — one query for the whole matrix. */
    public static List<CompetenceAttemptDecision> listForAttempts(List<String> attemptUuids) {
        if (attemptUuids == null || attemptUuids.isEmpty()) {
            return List.of();
        }
        return list("attemptUuid in ?1 order by decidedAt desc", attemptUuids);
    }
}

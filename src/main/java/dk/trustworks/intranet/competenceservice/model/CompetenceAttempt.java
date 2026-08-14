package dk.trustworks.intranet.competenceservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The evidence row: created when a test starts, scored once, then frozen.
 *
 * <p>Immutability is enforced by {@code trg_competence_attempt_before_update}, which
 * permits exactly three transitions and SIGNALs otherwise: the scoring write, the
 * reaper's abandon flag, and a GDPR pseudonymisation of {@code useruuid} to an
 * {@code erased:} value. DELETE is refused outright. The default application DB
 * connection is read-only, so a manual "fix" already needs a deliberate escalation;
 * these triggers make the escalation insufficient.
 *
 * <p><strong>Answers are never stored</strong> — only the aggregate. That is the
 * data-minimisation decision the concept makes explicitly ("kun hændelsen, ikke dine
 * svar"), and it is what lets the module keep records for years without holding an
 * assessment record on each employee.
 *
 * <p>{@code thresholdSnapshot} and {@code contentVersionUuid} are frozen at start rather
 * than read at scoring time: raising the pass threshold must never retroactively fail
 * somebody who already passed, and publishing a new test version mid-attempt must not
 * corrupt the attempt in flight.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "competence_attempt")
public class CompetenceAttempt extends PanacheEntityBase {

    /** Prefix marking a pseudonymised subject. The update trigger keys on it. */
    public static final String ERASED_PREFIX = "erased:";

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String useruuid;

    @Column(name = "requirement_uuid")
    private String requirementUuid;

    @Column(name = "content_version_uuid")
    private String contentVersionUuid;

    @Column(name = "version_label")
    private String versionLabel;

    /** Denormalised for the auditor export. */
    private String kref;

    @Column(name = "threshold_snapshot")
    private BigDecimal thresholdSnapshot;

    /**
     * The per-attempt option shuffle, {@code {"q1":["q1o3","q1o1",...]}}.
     *
     * <p>Held for the whole attempt so navigating back and forth does not move the
     * answers under the candidate. Server-side only; never returned after submit.
     */
    @Column(name = "option_order_json", columnDefinition = "TEXT")
    private String optionOrderJson;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** NULL = in progress. */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "question_count")
    private int questionCount;

    private BigDecimal score;

    private Boolean passed;

    /** Set by the reaper. Abandoned attempts never block a new start and never export. */
    private boolean abandoned;

    public boolean isInProgress() {
        return submittedAt == null && !abandoned;
    }

    public boolean isPassed() {
        return Boolean.TRUE.equals(passed);
    }

    public static CompetenceAttempt findByUuid(String uuid) {
        return find("uuid", uuid).firstResult();
    }

    /** The caller's open attempt for a requirement, if any. */
    public static CompetenceAttempt findOpen(String useruuid, String requirementUuid) {
        return find("useruuid = ?1 and requirementUuid = ?2 and submittedAt is null and abandoned = false",
                useruuid, requirementUuid).firstResult();
    }

    /**
     * Every open attempt this person holds, across all requirements — one query for the
     * whole "Mine kompetencekrav" page.
     *
     * <p>{@link #findOpen} answers the same question for a single requirement; calling it
     * once per card would be a query per row, which §10.10 asks the learner surface not to
     * do. There is normally at most one row per requirement here, but nothing at the
     * database level enforces that, so callers index by requirement and take the newest.
     */
    public static List<CompetenceAttempt> listOpenForUser(String useruuid) {
        return list("useruuid = ?1 and submittedAt is null and abandoned = false "
                + "order by startedAt desc", useruuid);
    }

    public static List<CompetenceAttempt> listForUser(String useruuid) {
        return list("useruuid = ?1 and submittedAt is not null order by submittedAt desc", useruuid);
    }

    /** Every submitted attempt for a set of users — one query for the whole matrix. */
    public static List<CompetenceAttempt> listSubmittedForUsers(List<String> useruuids) {
        if (useruuids == null || useruuids.isEmpty()) {
            return List.of();
        }
        return list("useruuid in ?1 and submittedAt is not null and abandoned = false "
                + "order by submittedAt desc", useruuids);
    }

    /** Passed attempts, oldest first — the approval queue's natural order. */
    public static List<CompetenceAttempt> listPassed() {
        return list("passed = true and abandoned = false and submittedAt is not null "
                + "order by submittedAt asc");
    }

    public static List<CompetenceAttempt> listStale(LocalDateTime startedBefore) {
        return list("submittedAt is null and abandoned = false and startedAt < ?1", startedBefore);
    }
}

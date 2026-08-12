package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reads and writes {@link RecruitmentReactorDeadLetter} rows (V490).
 * <p>
 * Split out of {@link RecruitmentReactor} because the writer is per-reactor
 * (a reactor dead-letters its own event) while both readers are global: the
 * catch-up batchlet's alarm asks "is anything OPEN anywhere?" once per run
 * rather than once per reactor, and the ops resource lists across reactors.
 * <p>
 * Every method runs in its own committed transaction — a dead letter must
 * survive precisely the delivery transaction that just rolled back.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentReactorDeadLetterService {

    @Inject
    EntityManager em;

    /**
     * Record (or re-open) the dead letter for one permanently skipped event.
     * <p>
     * Upsert rather than insert: a reactor that dead-letters, gets replayed
     * unsuccessfully, and dead-letters again must land on ONE row whose
     * error reflects the latest attempt — not a duplicate-key failure that
     * would propagate into the sweep and stall the reactor.
     */
    public void record(String reactorName, long eventSeq, int attempts, Throwable failure) {
        RecruitmentReactorDeadLetter.Cause cause = RecruitmentReactorDeadLetter.describeCause(failure);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "INSERT INTO recruitment_reactor_dead_letters "
                        + "(reactor_name, event_seq, attempts, error_class, error_message, status, "
                        + " dead_lettered_at, last_attempt_at) "
                        + "VALUES (:name, :seq, :attempts, :errClass, :errMessage, 'OPEN', :now, :now) "
                        + "ON DUPLICATE KEY UPDATE attempts = VALUES(attempts), "
                        + " error_class = VALUES(error_class), error_message = VALUES(error_message), "
                        + " status = 'OPEN', last_attempt_at = VALUES(last_attempt_at), "
                        + " resolved_at = NULL, resolved_by = NULL")
                .setParameter("name", reactorName)
                .setParameter("seq", eventSeq)
                .setParameter("attempts", attempts)
                .setParameter("errClass", cause.errorClass())
                .setParameter("errMessage", cause.errorMessage())
                .setParameter("now", now)
                .executeUpdate());
        log.warnf("Dead-lettered %s seq %d after %d attempts: %s — %s",
                reactorName, eventSeq, attempts, cause.errorClass(), cause.errorMessage());
    }

    /** How many dead letters still need a human, across every reactor. */
    public long countOpen() {
        return QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                        "SELECT COUNT(d) FROM RecruitmentReactorDeadLetter d WHERE d.status = :open",
                        Long.class)
                .setParameter("open", RecruitmentReactorDeadLetter.STATUS_OPEN)
                .getSingleResult());
    }

    /** OPEN dead letters, oldest first — the alarm detail and the ops list. */
    public List<RecruitmentReactorDeadLetter> listOpen(int limit) {
        return QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                        "SELECT d FROM RecruitmentReactorDeadLetter d WHERE d.status = :open "
                        + "ORDER BY d.deadLetteredAt ASC, d.eventSeq ASC",
                        RecruitmentReactorDeadLetter.class)
                .setParameter("open", RecruitmentReactorDeadLetter.STATUS_OPEN)
                .setMaxResults(limit)
                .getResultList());
    }

    /** One dead letter, or null. */
    public RecruitmentReactorDeadLetter find(String reactorName, long eventSeq) {
        return QuarkusTransaction.requiringNew().call(() -> em.find(RecruitmentReactorDeadLetter.class,
                new RecruitmentReactorDeadLetter.Key(reactorName, eventSeq)));
    }

    /**
     * Close a dead letter. Only an OPEN row moves, so a double-submit from
     * the ops UI cannot overwrite the first resolver's attribution.
     *
     * @return true when this call closed it
     */
    public boolean resolve(String reactorName, long eventSeq, String status, String actorUuid) {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                        "UPDATE recruitment_reactor_dead_letters "
                        + "SET status = :status, resolved_at = :now, resolved_by = :actor, "
                        + "    last_attempt_at = :now "
                        + "WHERE reactor_name = :name AND event_seq = :seq AND status = 'OPEN'")
                .setParameter("status", status)
                .setParameter("now", LocalDateTime.now(ZoneOffset.UTC))
                .setParameter("actor", actorUuid)
                .setParameter("name", reactorName)
                .setParameter("seq", eventSeq)
                .executeUpdate()) == 1;
    }

    /**
     * Refresh an OPEN row after a replay attempt that failed again — the
     * operator sees the current error, not the one from the original sweep.
     */
    public void recordFailedReplay(String reactorName, long eventSeq, Throwable failure) {
        RecruitmentReactorDeadLetter.Cause cause = RecruitmentReactorDeadLetter.describeCause(failure);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE recruitment_reactor_dead_letters "
                        + "SET attempts = attempts + 1, error_class = :errClass, "
                        + "    error_message = :errMessage, last_attempt_at = :now "
                        + "WHERE reactor_name = :name AND event_seq = :seq AND status = 'OPEN'")
                .setParameter("errClass", cause.errorClass())
                .setParameter("errMessage", cause.errorMessage())
                .setParameter("now", LocalDateTime.now(ZoneOffset.UTC))
                .setParameter("name", reactorName)
                .setParameter("seq", eventSeq)
                .executeUpdate());
    }
}

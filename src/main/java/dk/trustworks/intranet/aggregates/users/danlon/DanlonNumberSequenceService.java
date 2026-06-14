package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.domain.user.entity.DanlonNumberSequence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

/**
 * Supplies suggested Danløn numbers from the single high-water counter
 * (spec §4.2). The counter ONLY increments and is never recomputed from
 * user_danlon_history, so it can never hand back a freed number (AC4).
 * The advisory suggestion is HR's default; gaps (from rejected proposals)
 * are harmless.
 */
@JBossLog
@ApplicationScoped
public class DanlonNumberSequenceService {

    @Inject
    EntityManager em;

    /**
     * Atomically read-and-increment the counter under a pessimistic write
     * lock and return {@code "T" + value}. Joins the caller's transaction
     * (REQUIRED) so the lock is held until the caller commits, serialising
     * concurrent suggestions.
     */
    @Transactional
    public String nextSuggestedNumber() {
        DanlonNumberSequence seq = em.createQuery(
                        "SELECT s FROM DanlonNumberSequence s WHERE s.name = :n", DanlonNumberSequence.class)
                .setParameter("n", DanlonNumberSequence.DANLON)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        // managed entity is flushed at commit
        String suggested = "T" + value;
        log.debugf("Suggested Danløn number %s (counter advanced to %d)", suggested, value + 1);
        return suggested;
    }
}

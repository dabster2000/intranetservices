package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for recruitment event reactors (Slack notifier, mailer, AI
 * enrichment, GDPR clock, ... — first concrete reactors arrive in P9/P12).
 * Subclasses are {@code @ApplicationScoped} beans; discovery is via CDI
 * ({@code Instance<RecruitmentReactor>}).
 * <p>
 * <b>Delivery model — two cooperating paths:</b>
 * <ul>
 *   <li><b>Live:</b> after each commit the recorder publishes the event's
 *       {@code seq}; {@link RecruitmentEventDispatcher} calls
 *       {@link #deliverLive(long)}. The event is handled in its own
 *       transaction together with a durable per-event dedupe row
 *       ({@code recruitment_reactor_deliveries}) — handler failure rolls
 *       both back, so catch-up retries.</li>
 *   <li><b>Catch-up:</b> {@link #catchUp()} (batchlet, every 5 min) sweeps
 *       events between the reactor's watermark
 *       ({@code recruitment_reactor_offsets}) and the stream head in
 *       {@code seq} order and is the only path that advances the watermark.
 *       It only touches events older than a <em>grace horizon</em>
 *       ({@code dk.trustworks.recruitment.catchup.grace-seconds}): because
 *       {@code seq} is assigned at insert but events become visible at
 *       commit, a fresh region of the stream may still have in-flight
 *       transactions whose events would be skipped forever if the watermark
 *       swept past their unassigned gap. Beyond the horizon every seq is
 *       settled (committed or rolled back), so the sweep is gap-safe. Fresh
 *       events are the live path's job.</li>
 * </ul>
 * Together this yields: at-least-once delivery with exactly-once side
 * effects for anything transactional, exact dedupe across restarts for
 * external side effects (Slack/mail), no historical replay (watermark is
 * seeded to the stream head when a reactor first deploys — startup guard),
 * and in-order delivery on the catch-up path.
 * <p>
 * <b>Idempotency contract for subclasses:</b> {@link #handle} may still be
 * invoked more than once for the same event in rare races (e.g. live
 * delivery slower than the grace horizon). External side effects should
 * tolerate that; transactional side effects are already exactly-once.
 * <p>
 * <b>Poison events:</b> by default a failing event blocks the reactor's
 * watermark and is retried every cycle. Reactors that prefer to skip
 * poison events (AI spec §3.3: "one in-JVM try + one catch-up retry, then
 * swallow and advance") override {@link #maxDeliveryAttempts()}; skipped
 * events get a durable {@code SKIPPED} marker and the sweep moves on.
 */
@JBossLog
public abstract class RecruitmentReactor {

    /** Hard bound per sweep — backstop against a runaway loop, far above any real backlog. */
    private static final int MAX_EVENTS_PER_SWEEP = 10_000;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "dk.trustworks.recruitment.catchup.grace-seconds", defaultValue = "300")
    long catchupGraceSeconds;

    /** In-JVM attempt counter per event seq (cleared on success/skip; reset by restart — acceptable). */
    private final ConcurrentHashMap<Long, Integer> attempts = new ConcurrentHashMap<>();

    /**
     * Stable reactor identity — the {@code recruitment_reactor_offsets}
     * primary key. Max 100 chars; renaming a deployed reactor re-seeds it to
     * the stream head (events in between are never delivered to it).
     */
    public abstract String name();

    /**
     * React to one committed event. Runs inside the delivery transaction —
     * throwing rolls back all transactional work of this delivery and leaves
     * the event pending for catch-up retry.
     */
    protected abstract void handle(RecruitmentEvent event) throws Exception;

    /**
     * How many delivery attempts before the event is skipped (durable
     * {@code SKIPPED} marker, watermark advances past it). Default: retry
     * forever — the watermark blocks on the failing event.
     */
    protected int maxDeliveryAttempts() {
        return Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------
    // Live path
    // ------------------------------------------------------------------

    /**
     * Handle one freshly committed event (called by the dispatcher).
     * Exceptions propagate to the dispatcher (logged there); catch-up
     * retries the event later.
     */
    public void deliverLive(long seq) {
        // The startup guard normally seeds the offset row at boot. If a
        // reactor is somehow live before that, the event triggering us is
        // post-deploy by definition — seed the watermark just below it.
        ensureOffsetRow(seq - 1);
        DeliveryOutcome outcome = deliverOnce(seq);
        if (outcome == DeliveryOutcome.HANDLED) {
            log.debugf("Reactor %s handled event seq %d (live)", name(), seq);
        }
    }

    // ------------------------------------------------------------------
    // Catch-up path (the only writer of the watermark)
    // ------------------------------------------------------------------

    /**
     * Sweep all settled pending events in order. Called by the catch-up
     * batchlet and by the startup-recovery path; safe to call concurrently
     * from several instances (the watermark row lock serializes advances,
     * the dedupe row serializes handling).
     *
     * @return a summary for logging / batchlet exit status
     */
    public CatchUpSummary catchUp() {
        ensureOffsetRowSeededToHead();
        LocalDateTime horizon = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(catchupGraceSeconds);

        int handled = 0;
        int alreadyDone = 0;
        int skippedPoison = 0;
        boolean blocked = false;

        for (int i = 0; i < MAX_EVENTS_PER_SWEEP; i++) {
            Long next = findNextPendingSeq(horizon);
            if (next == null) {
                break;
            }
            try {
                DeliveryOutcome outcome = deliverOnce(next);
                if (outcome == DeliveryOutcome.HANDLED) {
                    handled++;
                } else {
                    alreadyDone++;
                }
            } catch (Exception e) {
                int attemptCount = attempts.getOrDefault(next, 0);
                if (attemptCount >= maxDeliveryAttempts()) {
                    log.errorf(e, "Reactor %s: event seq %d failed %d attempts — skipping (poison event)",
                            name(), next, attemptCount);
                    markSkipped(next);
                    skippedPoison++;
                } else {
                    log.warnf(e, "Reactor %s: event seq %d failed delivery (attempt %d) — sweep stops, retrying next cycle",
                            name(), next, attemptCount);
                    blocked = true;
                    break;
                }
            }
            advanceWatermarkTo(next);
        }
        return new CatchUpSummary(name(), handled, alreadyDone, skippedPoison, blocked);
    }

    // ------------------------------------------------------------------
    // Offset row lifecycle
    // ------------------------------------------------------------------

    /**
     * Seed the watermark to the current stream head if this reactor has no
     * offset row yet — a newly deployed reactor never replays history
     * (plan §2). No-op when the row exists. Called by the startup guard and
     * defensively before every sweep.
     */
    public void ensureOffsetRowSeededToHead() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "INSERT IGNORE INTO recruitment_reactor_offsets (reactor_name, last_processed_seq) " +
                                "SELECT :name, COALESCE(MAX(seq), 0) FROM recruitment_events")
                        .setParameter("name", name())
                        .executeUpdate());
    }

    private void ensureOffsetRow(long seedIfMissing) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "INSERT IGNORE INTO recruitment_reactor_offsets (reactor_name, last_processed_seq) " +
                                "VALUES (:name, :seed)")
                        .setParameter("name", name())
                        .setParameter("seed", Math.max(seedIfMissing, 0))
                        .executeUpdate());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private enum DeliveryOutcome {
        /** This call claimed and handled the event. */
        HANDLED,
        /** Another delivery (live/other instance) already handled it, or the watermark passed it. */
        ALREADY_DONE
    }

    /**
     * Claim + handle one event in a single new transaction. The dedupe row
     * insert is flushed <em>before</em> the handler runs, so a concurrent
     * delivery of the same event blocks on the row lock and then fails its
     * claim — exactly one handler execution commits per event.
     */
    private DeliveryOutcome deliverOnce(long seq) {
        attempts.merge(seq, 1, Integer::sum);
        DeliveryOutcome outcome;
        try {
            outcome = QuarkusTransaction.requiringNew().call(() -> {
                RecruitmentReactorOffset offset = em.find(RecruitmentReactorOffset.class, name());
                if (offset != null && seq <= offset.getLastProcessedSeq()) {
                    return DeliveryOutcome.ALREADY_DONE; // watermark already passed it
                }
                if (em.find(RecruitmentReactorDelivery.class, new RecruitmentReactorDelivery.Key(name(), seq)) != null) {
                    return DeliveryOutcome.ALREADY_DONE;
                }
                RecruitmentEvent event = em.find(RecruitmentEvent.class, seq);
                if (event == null) {
                    // Cannot happen for live deliveries (published after commit);
                    // treat defensively as done.
                    return DeliveryOutcome.ALREADY_DONE;
                }
                em.persist(new RecruitmentReactorDelivery(
                        name(), seq, RecruitmentReactorDelivery.STATUS_PROCESSED,
                        LocalDateTime.now(ZoneOffset.UTC)));
                em.flush(); // claim now — concurrent claimants block here, then dup-key
                handle(event);
                return DeliveryOutcome.HANDLED;
            });
        } catch (Exception e) {
            if (isDuplicateKey(e)) {
                // Lost the claim race — the winner handled it.
                attempts.remove(seq);
                return DeliveryOutcome.ALREADY_DONE;
            }
            throw e instanceof RuntimeException re
                    ? re
                    : new IllegalStateException("Reactor " + name() + " failed delivering seq " + seq, e);
        }
        attempts.remove(seq);
        return outcome;
    }

    /** Durable poison-skip marker + nothing else (no handler work in this transaction). */
    private void markSkipped(long seq) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "INSERT IGNORE INTO recruitment_reactor_deliveries (reactor_name, event_seq, status, processed_at) " +
                                "VALUES (:name, :seq, 'SKIPPED', :now)")
                        .setParameter("name", name())
                        .setParameter("seq", seq)
                        .setParameter("now", LocalDateTime.now(ZoneOffset.UTC))
                        .executeUpdate());
        attempts.remove(seq);
    }

    /**
     * Monotonically advance the watermark and prune dedupe rows it has
     * passed. The pessimistic lock serializes concurrent sweeps (two JVM
     * instances during ECS cutover).
     */
    private void advanceWatermarkTo(long seq) {
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentReactorOffset offset =
                    em.find(RecruitmentReactorOffset.class, name(), LockModeType.PESSIMISTIC_WRITE);
            if (offset == null) {
                return; // cannot happen — ensureOffsetRow* ran first
            }
            if (seq > offset.getLastProcessedSeq()) {
                offset.setLastProcessedSeq(seq);
            }
            em.createQuery("DELETE FROM RecruitmentReactorDelivery d " +
                            "WHERE d.reactorName = :name AND d.eventSeq <= :seq")
                    .setParameter("name", name())
                    .setParameter("seq", offset.getLastProcessedSeq())
                    .executeUpdate();
        });
    }

    /**
     * Current watermark of this reactor (0 when the reactor has never been
     * seeded). For tests and operational introspection.
     */
    public long watermark() {
        return QuarkusTransaction.requiringNew().call(() -> {
            RecruitmentReactorOffset offset = em.find(RecruitmentReactorOffset.class, name());
            return offset == null ? 0L : offset.getLastProcessedSeq();
        });
    }

    private Long findNextPendingSeq(LocalDateTime horizon) {
        // Own transaction: catch-up runs on batch/worker threads where
        // neither a transaction nor a request context is active, and the
        // lazily-bound EntityManager needs one of the two.
        return QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                        "SELECT e.seq FROM RecruitmentEvent e " +
                        "WHERE e.seq > (SELECT o.lastProcessedSeq FROM RecruitmentReactorOffset o WHERE o.reactorName = :name) " +
                        "AND e.occurredAt <= :horizon ORDER BY e.seq ASC", Long.class)
                .setParameter("name", name())
                .setParameter("horizon", horizon)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null));
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    /** Result of one catch-up sweep, for logs and the batchlet exit status. */
    public record CatchUpSummary(String reactor, int handled, int alreadyDone, int skippedPoison, boolean blocked) {

        @Override
        public String toString() {
            return "%s[handled=%d, alreadyDone=%d, skippedPoison=%d%s]"
                    .formatted(reactor, handled, alreadyDone, skippedPoison, blocked ? ", BLOCKED" : "");
        }
    }
}

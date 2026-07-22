package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventTestSupport.MARKER_KEY;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventTestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 DoD: reactor throws on first delivery → catch-up redelivers → offset
 * advances exactly once; duplicate delivery → single side effect. Plus the
 * seeding rule: a new reactor starts at the stream head — no historical
 * replay — and the catch-up grace horizon leaves fresh events to the live
 * path.
 */
@QuarkusTest
class RecruitmentReactorIntegrationTest {

    @Inject
    RecruitmentEventRecorder recorder;

    @Inject
    CountingTestReactor countingReactor;

    @Inject
    SkippingTestReactor skippingReactor;

    @Inject
    EntityManager em;

    @Inject
    EventBus eventBus;

    private RecruitmentEvent recordMarked(String marker) {
        return recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.CANDIDATE_UPDATED)
                        .candidate(UUID.randomUUID().toString())
                        .actorSystem()
                        .payload(MARKER_KEY, marker));
    }

    private int sideEffects(String marker) {
        AtomicInteger n = CountingTestReactor.SIDE_EFFECTS.get(marker);
        return n == null ? 0 : n.get();
    }

    private int attempts(String marker) {
        AtomicInteger n = CountingTestReactor.ATTEMPTS.get(marker);
        return n == null ? 0 : n.get();
    }

    private boolean deliveryRowExists(String reactorName, long seq) {
        // COUNT query + clear: the test thread's request-scoped session
        // caches earlier find() results and would not see the sweep's bulk
        // JPQL prune (which bypasses the persistence context by design).
        em.clear();
        return em.createQuery(
                        "SELECT COUNT(d) FROM RecruitmentReactorDelivery d "
                                + "WHERE d.reactorName = :name AND d.eventSeq = :seq", Long.class)
                .setParameter("name", reactorName)
                .setParameter("seq", seq)
                .getSingleResult() > 0;
    }

    @Test
    void liveDelivery_handlesExactlyOnce_andSweepDedupes() {
        String marker = "m1-" + UUID.randomUUID();

        RecruitmentEvent event = recordMarked(marker);
        long seq = event.getSeq();

        assertTrue(awaitTrue(() -> sideEffects(marker) == 1, 5_000),
                "live path must handle the committed event");
        assertTrue(awaitTrue(() -> deliveryRowExists(CountingTestReactor.NAME, seq), 5_000),
                "live delivery must leave a durable dedupe row");

        // The sweep must recognize the live delivery, advance the watermark
        // past it, prune the dedupe row — and not re-run the handler.
        countingReactor.catchUp();
        assertEquals(1, sideEffects(marker), "sweep must not double-handle a live-delivered event");
        assertTrue(countingReactor.watermark() >= seq, "watermark must cover the swept event");
        assertFalse(deliveryRowExists(CountingTestReactor.NAME, seq),
                "dedupe row must be pruned once the watermark passed it");

        // Duplicate live delivery after the sweep: watermark filters it.
        countingReactor.deliverLive(seq);
        assertEquals(1, sideEffects(marker), "duplicate delivery must cause no second side effect");
    }

    @Test
    void failedLiveDelivery_catchUpRedelivers_offsetAdvancesExactlyOnce() throws Exception {
        String marker = "m2-" + UUID.randomUUID();
        CountingTestReactor.FAILURES_REMAINING.put(marker, new AtomicInteger(1));

        RecruitmentEvent event = recordMarked(marker);
        long seq = event.getSeq();

        assertTrue(awaitTrue(() -> attempts(marker) == 1, 5_000),
                "live path must attempt the event");
        assertEquals(0, sideEffects(marker), "failed delivery must produce no side effect");
        assertFalse(deliveryRowExists(CountingTestReactor.NAME, seq),
                "failed delivery must roll back its dedupe row");

        // Catch-up redelivers exactly once.
        countingReactor.catchUp();
        assertEquals(1, sideEffects(marker), "catch-up must redeliver the failed event once");
        assertEquals(2, attempts(marker));
        long watermarkAfterFirstSweep = countingReactor.watermark();
        assertTrue(watermarkAfterFirstSweep >= seq, "offset must advance after successful redelivery");

        // Second sweep: nothing more to do for this event.
        countingReactor.catchUp();
        assertEquals(1, sideEffects(marker));

        // Duplicate live deliveries — direct and via the bus — are filtered.
        countingReactor.deliverLive(seq);
        eventBus.publish(RecruitmentEventRecorder.EVENT_BUS_ADDRESS, seq);
        Thread.sleep(500);
        assertEquals(1, sideEffects(marker), "duplicate delivery must cause a single total side effect");
        assertEquals(2, attempts(marker), "duplicates must not even reach the handler");
    }

    @Test
    void poisonEvent_isSkippedAfterMaxAttempts_andWatermarkMovesOn() {
        String marker = "m3-" + UUID.randomUUID();
        SkippingTestReactor.POISON_MARKERS.add(marker);
        try {
            RecruitmentEvent event = recordMarked(marker);
            long seq = event.getSeq();

            assertTrue(awaitTrue(() -> SkippingTestReactor.ATTEMPTS.containsKey(marker)
                            && SkippingTestReactor.ATTEMPTS.get(marker).get() == 1, 5_000),
                    "live path must make the first attempt");

            // Sweep: attempt 2 fails, maxDeliveryAttempts()==2 → skip + advance.
            RecruitmentReactor.CatchUpSummary summary = skippingReactor.catchUp();
            assertEquals(2, SkippingTestReactor.ATTEMPTS.get(marker).get());
            assertTrue(summary.skippedPoison() >= 1, "sweep must report the poison skip: " + summary);
            assertTrue(skippingReactor.watermark() >= seq, "poison event must not block the watermark");
            assertNull(SkippingTestReactor.SIDE_EFFECTS.get(marker));

            // Further sweeps never touch it again.
            skippingReactor.catchUp();
            assertEquals(2, SkippingTestReactor.ATTEMPTS.get(marker).get(),
                    "a skipped poison event must never be re-attempted");
        } finally {
            SkippingTestReactor.POISON_MARKERS.remove(marker);
        }
    }

    @Test
    void newReactor_seedsToHead_neverReplaysHistory() {
        // History that must NOT be replayed to a reactor deployed later.
        RecruitmentEvent historical = recordMarked("m4-historical-" + UUID.randomUUID());

        ProbeReactor probe = new ProbeReactor("test-seed-" + UUID.randomUUID(), em, 0);
        RecruitmentReactor.CatchUpSummary first = probe.catchUp();
        assertEquals(0, first.handled(), "a freshly seeded reactor must process nothing");
        assertTrue(probe.watermark() >= historical.getSeq(),
                "seeding must place the watermark at (or past) the stream head");
        assertFalse(probe.seen.contains(historical.getSeq()));

        // Events appended after the seed ARE delivered, in order.
        RecruitmentEvent fresh = recordMarked("m4-fresh-" + UUID.randomUUID());
        probe.catchUp();
        assertTrue(probe.seen.contains(fresh.getSeq()), "post-seed events must be delivered by catch-up");
        assertFalse(probe.seen.contains(historical.getSeq()), "pre-seed history must stay unplayed");
        assertTrue(probe.watermark() >= fresh.getSeq());
    }

    @Test
    void graceHorizon_leavesFreshEventsToTheLivePath() {
        ProbeReactor probe = new ProbeReactor("test-grace-" + UUID.randomUUID(), em, 3_600);
        probe.catchUp(); // seed at head

        RecruitmentEvent fresh = recordMarked("m5-" + UUID.randomUUID());
        probe.catchUp();
        assertFalse(probe.seen.contains(fresh.getSeq()),
                "events younger than the grace horizon must not be swept");

        probe.catchupGraceSeconds = 0;
        probe.catchUp();
        assertTrue(probe.seen.contains(fresh.getSeq()),
                "the same event must be swept once it is older than the horizon");
    }

    /**
     * Manually instantiated (non-CDI) reactor: only sees events through its
     * own catchUp() calls — exactly what the seeding/horizon tests need.
     */
    static final class ProbeReactor extends RecruitmentReactor {

        final List<Long> seen = new CopyOnWriteArrayList<>();
        private final String reactorName;

        ProbeReactor(String reactorName, EntityManager em, long graceSeconds) {
            this.reactorName = reactorName;
            this.em = em;
            this.catchupGraceSeconds = graceSeconds;
        }

        @Override
        public String name() {
            return reactorName;
        }

        @Override
        protected void handle(RecruitmentEvent event) {
            seen.add(event.getSeq());
        }
    }
}

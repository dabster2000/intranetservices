package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventTestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 DoD: {@code record()} inside a transaction that commits → row present
 * and EventBus message observed strictly after commit; inside a transaction
 * that rolls back → no row, no message.
 */
@QuarkusTest
class RecruitmentEventRecorderIntegrationTest {

    @Inject
    RecruitmentEventRecorder recorder;

    @Inject
    EntityManager em;

    @Inject
    Vertx vertx;

    private final List<Long> receivedSeqs = new CopyOnWriteArrayList<>();
    private MessageConsumer<Long> consumer;

    @BeforeEach
    void registerBusProbe() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        consumer = vertx.eventBus().consumer(RecruitmentEventRecorder.EVENT_BUS_ADDRESS,
                msg -> receivedSeqs.add(msg.body()));
        consumer.completionHandler(v -> registered.countDown());
        assertTrue(registered.await(5, TimeUnit.SECONDS), "bus probe registration timed out");
    }

    @AfterEach
    void unregisterBusProbe() {
        if (consumer != null) {
            consumer.unregister();
        }
    }

    @Test
    void record_inCommittedTransaction_persistsRow_andPublishesAfterCommit() {
        QuarkusTransaction.begin();
        RecruitmentEvent event = recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.POSITION_OPENED)
                        .position(UUID.randomUUID().toString())
                        .actorSystem()
                        .payload("hiring_track", "PRACTICE_TEAM")
                        .payload("stage_count", 5));
        Long seq = event.getSeq();
        assertNotNull(seq, "IDENTITY seq must be assigned at persist time");
        assertNotNull(event.getEventId());

        // The publish is deferred to after-commit — nothing may be on the bus yet.
        assertFalse(receivedSeqs.contains(seq), "message must not be published before commit");
        QuarkusTransaction.commit();

        assertTrue(awaitTrue(() -> receivedSeqs.contains(seq), 5_000),
                "seq " + seq + " must be published after commit");

        RecruitmentEvent persisted = em.find(RecruitmentEvent.class, seq);
        assertNotNull(persisted, "event row must survive the commit");
        assertEquals(RecruitmentEventType.POSITION_OPENED, persisted.getEventType());
        assertEquals(RecruitmentActorType.SYSTEM, persisted.getActorType());
        assertNull(persisted.getActorUuid());
        assertEquals(RecruitmentEventVisibility.NORMAL, persisted.getVisibility(), "default visibility");
        assertEquals(RecruitmentPiiState.NONE, persisted.getPiiState(), "no pii section → NONE");
        assertNull(persisted.getPii());
        assertNotNull(persisted.getOccurredAt());
        assertTrue(persisted.getPayload().contains("PRACTICE_TEAM"));
        // v7 identity: parseable UUID with version 7
        assertEquals(7, UUID.fromString(persisted.getEventId()).version());
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(persisted);
    }

    @Test
    void record_inRolledBackTransaction_leavesNoRow_andNoMessage() throws Exception {
        QuarkusTransaction.begin();
        RecruitmentEvent event = recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.POSITION_UPDATED)
                        .position(UUID.randomUUID().toString())
                        .actorSystem()
                        .payload("field", "status"));
        Long seq = event.getSeq();
        String eventId = event.getEventId();
        QuarkusTransaction.rollback();

        // Give a stray publish every chance to arrive before asserting absence.
        Thread.sleep(750);
        assertFalse(receivedSeqs.contains(seq), "rolled-back event must not be published");
        assertNull(em.find(RecruitmentEvent.class, seq), "rolled-back event must leave no row");
        Long byEventId = em.createQuery(
                        "SELECT COUNT(e) FROM RecruitmentEvent e WHERE e.eventId = :id", Long.class)
                .setParameter("id", eventId)
                .getSingleResult();
        assertEquals(0L, byEventId);
    }

    @Test
    void record_withoutSurroundingTransaction_persistsAndPublishesImmediately() {
        RecruitmentEvent event = recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.POSITION_CLOSED)
                        .position(UUID.randomUUID().toString())
                        .actorScheduler()
                        .payload("reason_code", "FILLED"));
        Long seq = event.getSeq();
        assertTrue(awaitTrue(() -> receivedSeqs.contains(seq), 5_000),
                "no-transaction record must publish right away");
        assertNotNull(em.find(RecruitmentEvent.class, seq));
    }

    @Test
    void record_withPiiSection_marksPiiStatePresent_andKeepsPayloadClean() {
        RecruitmentEvent event = recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.NOTE_ADDED)
                        .candidate(UUID.randomUUID().toString())
                        .actorUser(UUID.randomUUID().toString())
                        .payload("private", true)
                        .pii("note_text", "Salary expectation 55.000 DKK — do not share"));
        assertEquals(RecruitmentPiiState.PRESENT, event.getPiiState());
        assertNotNull(event.getPii());
        assertTrue(event.getPii().contains("55.000"));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void record_missingActor_isRejected_beforeAnyPersist() {
        assertThrows(IllegalArgumentException.class, () -> recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.CANDIDATE_CREATED)
                        .candidate(UUID.randomUUID().toString())));
    }

    @Test
    void record_userActorWithoutUuid_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> recorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.CANDIDATE_CREATED)
                        .candidate(UUID.randomUUID().toString())
                        .actorUser(" ")));
    }
}

package dk.trustworks.intranet.recruitmentservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * The single write path to the recruitment event stream.
 * <p>
 * {@link #record(RecruitmentEventBuilder)} persists the event <em>in the
 * caller's transaction</em> — state tables and the event store can never
 * diverge because there is only one write (spec §3.2) — and registers an
 * after-commit EventBus publish of the event's {@code seq} on
 * {@link #EVENT_BUS_ADDRESS}, mirroring {@code AggregateEventSender}. A
 * rolled-back transaction therefore leaves no row and emits no message.
 * <p>
 * <b>Rule (ArchUnit-enforced):</b> no other class persists
 * {@link RecruitmentEvent}. See {@code RecruitmentEventSingleWriterArchTest}.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentEventRecorder {

    /**
     * In-JVM EventBus address carrying the {@code seq} (Long) of each
     * committed recruitment event. Consumed by
     * {@link RecruitmentEventDispatcher}, which fans out to the registered
     * {@link RecruitmentReactor}s.
     */
    public static final String EVENT_BUS_ADDRESS = "recruitment.events";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

    @Inject
    EventBus eventBus;

    /**
     * Validate, persist (caller's transaction when one is active, otherwise a
     * new one) and schedule the after-commit publish.
     *
     * @return the persisted event; {@code seq} and {@code eventId} are
     *         assigned. Note: on rollback of the surrounding transaction the
     *         row is gone — never hold on to the return value beyond the
     *         transaction.
     */
    public RecruitmentEvent record(RecruitmentEventBuilder builder) {
        RecruitmentEvent event = toEntity(builder);
        persistEvent(event);
        publishAfterCommit(event.getSeq());
        return event;
    }

    private RecruitmentEvent toEntity(RecruitmentEventBuilder builder) {
        if (builder.actorType() == null) {
            throw new IllegalArgumentException(
                    "Recruitment event " + builder.type() + " has no actor — call actorUser/actorSystem/actorCandidate/actorScheduler");
        }
        if (builder.actorType() == RecruitmentActorType.USER
                && (builder.actorUuid() == null || builder.actorUuid().isBlank())) {
            throw new IllegalArgumentException(
                    "Recruitment event " + builder.type() + " has actor type USER but no actor uuid");
        }

        RecruitmentEvent event = new RecruitmentEvent();
        event.eventId = UuidV7.generate().toString();
        event.eventType = builder.type();
        event.candidateUuid = builder.candidateUuid();
        event.applicationUuid = builder.applicationUuid();
        event.positionUuid = builder.positionUuid();
        event.actorUuid = builder.actorUuid();
        event.actorType = builder.actorType();
        event.occurredAt = LocalDateTime.now(ZoneOffset.UTC);
        event.visibility = builder.visibility();
        event.payload = toJson(builder.type(), "payload", builder.payloadMap());
        event.pii = toJson(builder.type(), "pii", builder.piiMap());
        event.piiState = event.pii == null ? RecruitmentPiiState.NONE : RecruitmentPiiState.PRESENT;
        return event;
    }

    private String toJson(RecruitmentEventType type, String section, Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            // Deliberately no map contents in the message — pii may be in there.
            throw new IllegalArgumentException(
                    "Recruitment event " + type + " has a non-serializable " + section + " section", e);
        }
    }

    /**
     * REQUIRED semantics — join the caller's transaction when one is active
     * (one JDBC connection total), otherwise start a new one. Same rationale
     * as {@code AggregateEventSender} (prod 500s on 2026-05-19).
     */
    private void persistEvent(RecruitmentEvent event) {
        if (QuarkusTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
            event.persist();
        } else {
            QuarkusTransaction.requiringNew().run(event::persist);
        }
    }

    /**
     * Defer the EventBus publish until after the surrounding transaction
     * commits, so reactors always see committed data. Without an active
     * transaction (the persist already committed via requiringNew) publish
     * immediately.
     */
    private void publishAfterCommit(Long seq) {
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        eventBus.publish(EVENT_BUS_ADDRESS, seq);
                    }
                }
            });
        } catch (Exception e) {
            // No active transaction — the persist committed in its own
            // transaction above, publish now.
            eventBus.publish(EVENT_BUS_ADDRESS, seq);
        }
    }
}

package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * One row of the append-only recruitment event stream (spec §3.3 envelope).
 * <p>
 * <b>Single write path:</b> only {@link RecruitmentEventRecorder} may persist
 * instances of this entity — enforced by an ArchUnit test
 * ({@code RecruitmentEventSingleWriterArchTest}) and code review. Command
 * handlers describe events with {@link RecruitmentEventBuilder} and hand them
 * to the recorder; they never touch this class directly.
 * <p>
 * The entity is deliberately immutable from Java (no setters): the only
 * mutation the event store ever sees is GDPR anonymization rewriting
 * {@code pii}/{@code pii_state} — since P19 that path exists and lives
 * exclusively in {@code RecruitmentAnonymizerService} (bulk update, exempted
 * by name in the ArchUnit rule).
 */
@Getter
@Entity
@Table(name = "recruitment_events")
public class RecruitmentEvent extends PanacheEntityBase {

    /** Global total order. Reactor offsets and the live-dispatch bus message key on this. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false, updatable = false)
    Long seq;

    /** UUIDv7 (time-ordered). Globally unique identity and idempotency key. */
    @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 64, nullable = false, updatable = false)
    RecruitmentEventType eventType;

    /** Soft FK to {@code recruitment_candidates.uuid}; null for position-only events. */
    @Column(name = "candidate_uuid", length = 36, updatable = false)
    String candidateUuid;

    /** Soft FK to {@code recruitment_applications.uuid} (table arrives in P4). */
    @Column(name = "application_uuid", length = 36, updatable = false)
    String applicationUuid;

    /** Soft FK to {@code recruitment_positions.uuid} (table arrives in P2). */
    @Column(name = "position_uuid", length = 36, updatable = false)
    String positionUuid;

    /** Soft FK to {@code users.uuid} (X-Requested-By); null unless {@code actorType == USER}. */
    @Column(name = "actor_uuid", length = 36, updatable = false)
    String actorUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 20, nullable = false, updatable = false)
    RecruitmentActorType actorType;

    /** UTC, millisecond precision. Set by the recorder at append time. */
    @Column(name = "occurred_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 10, nullable = false, updatable = false)
    RecruitmentEventVisibility visibility;

    /**
     * Structural facts only (stage codes, template ids, counts) as a JSON
     * object — NEVER personal data. The payload/pii split is the
     * anonymization contract; the shared test fixture
     * {@code assertNoPiiInPayload} guards it in every phase.
     */
    @Column(name = "payload", columnDefinition = "JSON", updatable = false)
    String payload;

    /**
     * The ONLY place personal data may appear. Rewritten to
     * <code>{"anonymized": true}</code> by GDPR anonymization (P19) — the
     * single permitted mutation of this table.
     */
    @Column(name = "pii", columnDefinition = "JSON")
    String pii;

    @Enumerated(EnumType.STRING)
    @Column(name = "pii_state", length = 12, nullable = false)
    RecruitmentPiiState piiState;
}

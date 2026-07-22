package dk.trustworks.intranet.recruitmentservice.events;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent description of a recruitment event, handed to
 * {@link RecruitmentEventRecorder#record(RecruitmentEventBuilder)} — the
 * single write path. Command handlers use this builder and never construct
 * or persist {@link RecruitmentEvent} directly.
 * <p>
 * The payload/pii split is the anonymization contract (spec §3.3):
 * {@link #payload(String, Object)} takes structural facts only (stage codes,
 * template ids, counts); every personal-data fragment — names in prose, note
 * text, email bodies, salary expectations — must go through
 * {@link #pii(String, Object)}. The shared test fixture
 * {@code assertNoPiiInPayload} enforces this in every phase's tests.
 */
public final class RecruitmentEventBuilder {

    private final RecruitmentEventType type;
    private String candidateUuid;
    private String applicationUuid;
    private String positionUuid;
    private String actorUuid;
    private RecruitmentActorType actorType;
    private RecruitmentEventVisibility visibility = RecruitmentEventVisibility.NORMAL;
    private final Map<String, Object> payload = new LinkedHashMap<>();
    private final Map<String, Object> pii = new LinkedHashMap<>();

    private RecruitmentEventBuilder(RecruitmentEventType type) {
        this.type = Objects.requireNonNull(type, "event type must not be null");
    }

    public static RecruitmentEventBuilder event(RecruitmentEventType type) {
        return new RecruitmentEventBuilder(type);
    }

    public RecruitmentEventBuilder candidate(String candidateUuid) {
        this.candidateUuid = candidateUuid;
        return this;
    }

    public RecruitmentEventBuilder application(String applicationUuid) {
        this.applicationUuid = applicationUuid;
        return this;
    }

    public RecruitmentEventBuilder position(String positionUuid) {
        this.positionUuid = positionUuid;
        return this;
    }

    /** The event was caused by an intranet user (UUID from X-Requested-By). */
    public RecruitmentEventBuilder actorUser(String userUuid) {
        this.actorType = RecruitmentActorType.USER;
        this.actorUuid = userUuid;
        return this;
    }

    /** The event was caused by the system itself (bridges, public intake). */
    public RecruitmentEventBuilder actorSystem() {
        this.actorType = RecruitmentActorType.SYSTEM;
        this.actorUuid = null;
        return this;
    }

    /** The event was caused by the candidate through a public surface. */
    public RecruitmentEventBuilder actorCandidate() {
        this.actorType = RecruitmentActorType.CANDIDATE;
        this.actorUuid = null;
        return this;
    }

    /** The event was caused by a scheduled job. */
    public RecruitmentEventBuilder actorScheduler() {
        this.actorType = RecruitmentActorType.SCHEDULER;
        this.actorUuid = null;
        return this;
    }

    public RecruitmentEventBuilder visibility(RecruitmentEventVisibility visibility) {
        this.visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        return this;
    }

    /**
     * Add a structural fact. NEVER personal data — that belongs in
     * {@link #pii(String, Object)}.
     */
    public RecruitmentEventBuilder payload(String key, Object value) {
        this.payload.put(requireKey(key), value);
        return this;
    }

    /** Add a personal-data fragment (rewritten on GDPR anonymization). */
    public RecruitmentEventBuilder pii(String key, Object value) {
        this.pii.put(requireKey(key), value);
        return this;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("JSON key must not be null or blank");
        }
        return key;
    }

    // --- package-private accessors for the recorder ----------------------

    RecruitmentEventType type() {
        return type;
    }

    String candidateUuid() {
        return candidateUuid;
    }

    String applicationUuid() {
        return applicationUuid;
    }

    String positionUuid() {
        return positionUuid;
    }

    String actorUuid() {
        return actorUuid;
    }

    RecruitmentActorType actorType() {
        return actorType;
    }

    RecruitmentEventVisibility visibility() {
        return visibility;
    }

    Map<String, Object> payloadMap() {
        return Collections.unmodifiableMap(payload);
    }

    Map<String, Object> piiMap() {
        return Collections.unmodifiableMap(pii);
    }
}

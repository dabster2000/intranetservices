package dk.trustworks.intranet.recruitmentservice.events;

/**
 * Who caused a recruitment event (spec §3.3 envelope).
 */
public enum RecruitmentActorType {
    /** An intranet user; {@code actor_uuid} carries the user UUID from X-Requested-By. */
    USER,
    /** The system itself (e.g. public form intake, bridges); {@code actor_uuid} is null. */
    SYSTEM,
    /** The candidate acting through a public surface (application form, consent page). */
    CANDIDATE,
    /** A scheduled job (catch-up, GDPR clock, digests). */
    SCHEDULER
}

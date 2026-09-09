package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.utils.json.UtcInstant;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One entry of the P8 candidate timeline ({@code ITimelineEvent} in the
 * FE↔BE contract). {@code payload} and {@code pii} are the event's JSON
 * sections <em>parsed</em> into objects on the wire — the frontend never
 * re-parses strings. {@code pii} is {@code null} when the event has no
 * personal data OR when scoping withheld it; {@code piiRedacted} is
 * {@code true} only in the withheld case (salary-expectation notes outside
 * the comp tier), so the UI can render an explicit "restricted" marker.
 * <p>
 * {@code compAmountsMasked} is the softer sibling: the pii IS present, but
 * money figures inside its free text were replaced with
 * {@code CompensationTextRedactor.MASK} because the viewer is outside the
 * comp tier. The two are mutually exclusive per event — a withheld pii has
 * no text left to mask.
 */
public record TimelineEvent(
        long seq,
        String eventId,
        RecruitmentEventType eventType,
        /** UTC instant — stamped by {@code RecruitmentEventRecorder} at append time. */
        @UtcInstant LocalDateTime occurredAt,
        RecruitmentActorType actorType,
        String actorUuid,
        /** Resolved "First Last" for USER actors; null for SYSTEM/CANDIDATE. */
        String actorName,
        String positionUuid,
        /** Resolved position title when the subject is present; else null. */
        String positionName,
        String applicationUuid,
        /** Structural facts, parsed JSON — never personal data. */
        Map<String, Object> payload,
        /** Personal data, parsed JSON; null when absent or withheld. */
        Map<String, Object> pii,
        /** True when {@code pii} was withheld by comp-tier scoping. */
        boolean piiRedacted,
        /**
         * True when {@code pii} survived but had compensation amounts masked
         * out of its free text for this viewer.
         */
        boolean compAmountsMasked
) {
}

package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The candidate option page's view (plan §11.1, spec §16.1) — everything
 * the ANONYMOUS token holder may see, and nothing else. The spec's
 * "should not see" list is the contract: no interviewer identities, no
 * approval states, no room mailboxes or names, no hold/event identifiers,
 * no internal reasons. Times are wall-clock in {@link #timezone}.
 *
 * @param status            OPEN = choose; SELECTED = a choice is committed
 *                          and the meeting is being booked
 * @param candidateFirstName greeting only (the mail recipient — the
 *                          consent-page precedent)
 * @param positionTitle     public context (job postings are public);
 *                          null for informal chats without a position
 * @param kind              ROUND, INFORMAL or OFFER
 * @param round             1..3 for ROUND, null otherwise
 * @param durationMinutes   interview length
 * @param timezone          always Europe/Copenhagen — shown explicitly
 * @param locationKind      ONLINE (Teams) or IN_PERSON
 * @param locationLabel     the request's PII-free location text for
 *                          IN_PERSON, when one was given; never a room
 *                          mailbox
 * @param deadline          answer-by (the option batch's expiry)
 * @param options           currently valid options, earliest first
 * @param selectedOptionId  the committed choice when {@code status} is
 *                          SELECTED
 */
public record PublicSchedulingResponse(
        String status,
        String candidateFirstName,
        String positionTitle,
        RecruitmentInterviewKind kind,
        Integer round,
        int durationMinutes,
        String timezone,
        String locationKind,
        String locationLabel,
        LocalDateTime deadline,
        List<PublicOption> options,
        String selectedOptionId
) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_SELECTED = "SELECTED";
    public static final String LOCATION_ONLINE = "ONLINE";
    public static final String LOCATION_IN_PERSON = "IN_PERSON";

    /** One selectable option — id + interval, nothing internal. */
    public record PublicOption(String optionId, LocalDateTime start, LocalDateTime end) {
    }
}

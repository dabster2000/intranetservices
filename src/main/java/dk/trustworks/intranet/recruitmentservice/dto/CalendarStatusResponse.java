package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Envelope of {@code GET /recruitment/interviews/{uuid}/calendar-status}
 * — the Outlook event's live RSVP state and time-drift check, read
 * on-demand (dialog open / tab expand; no webhooks). Empty
 * {@code rsvps} + {@code known=false} when the interview has no Outlook
 * event, the Graph toggle is off, or the read failed — the UI then
 * simply shows no badges.
 */
public record CalendarStatusResponse(
        /** Whether the Outlook event could actually be read. */
        boolean known,
        List<Rsvp> rsvps,
        /** True when Outlook's start differs from the interview row's. */
        boolean drifted,
        /** Outlook's start (wall-clock Europe/Copenhagen) when known. */
        LocalDateTime outlookStart
) {

    /**
     * One participant's response. {@code participantType} is INTERVIEWER
     * or CANDIDATE (the room's own auto-response is ignored);
     * {@code userUuid} is the user UUID for interviewers and the
     * candidate UUID for the candidate. {@code response} is one of
     * ACCEPTED, DECLINED, TENTATIVE, NONE, MISSING.
     * <p>
     * MISSING is not an answer — it means the person is an assigned
     * interviewer who appears on NO attendee line of the Outlook event,
     * i.e. they were never invited. It exists because the V492 attendee
     * drop was invisible for exactly as long as an uninvited interviewer
     * produced no row at all; a caller must render it distinctly from
     * NONE ("invited, has not replied"), never fold the two together.
     */
    public record Rsvp(String participantType, String userUuid, String response) {
    }
}

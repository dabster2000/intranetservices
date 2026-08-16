package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope of {@code GET /recruitment/interviews/interviewer-availability}
 * — Outlook free/busy per potential interviewer for one interview slot,
 * from the same Graph {@code getSchedule} lookup the room picker uses.
 * Empty when the Graph calendar toggle is off or the lookup fails (the UI
 * then shows the interviewer list without availability markers).
 * <p>
 * {@code availabilityComplete} is false when some mailboxes were never
 * successfully asked about (Graph throttled or errored). Their rows still
 * carry {@code available = null} and must still render unmarked — the
 * flag simply lets the UI say the picture is partial instead of implying
 * those people have empty calendars.
 */
public record InterviewerAvailabilityResponse(
        List<InterviewerAvailability> availability,
        int totalCount,
        boolean availabilityComplete
) {

    /** One interviewer's free/busy for the slot. Strictly free/busy — no
     * event subjects or details ever ride here. {@code available} is
     * {@code true} only when the whole slot is fully free (tentative and
     * out-of-office count as busy, same rule as rooms); {@code null} =
     * unknown (no mailbox, or the Graph lookup failed for this user) —
     * callers should show the person unmarked rather than as busy. */
    public record InterviewerAvailability(String userUuid, Boolean available) {
    }
}

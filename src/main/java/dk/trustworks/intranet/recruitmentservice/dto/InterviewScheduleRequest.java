package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reschedule an interview (P11): a new time is mandatory; location, room
 * and the interviewer list are optional (null = keep current value; a
 * blank string clears location/room). Changing interviewers is allowed —
 * a submitted scorecard from a removed interviewer is kept and still
 * counts in the debrief.
 *
 * @param scheduledAt         required, wall-clock Europe/Copenhagen as
 *                            entered (the Graph bridge stamps the timezone)
 * @param location            optional replacement location
 * @param roomEmail           optional replacement room mailbox (null = keep,
 *                            blank = clear the booking)
 * @param interviewerUuids    optional replacement interviewer list (1–10)
 * @param durationMinutes     optional replacement length in minutes
 *                            (15..480); null = keep the current length
 * @param onlineMeeting       optional: TRUE turns the Outlook event into a
 *                            Teams meeting (works on existing events too —
 *                            Phase 0.3 spike); null = keep current. FALSE
 *                            is accepted but never strips Teams from an
 *                            existing event (Graph one-way semantics).
 * @param createCalendarEvent optional: TRUE creates the missing Outlook
 *                            invitation for an unsynced interview (pre-toggle
 *                            and Airtable-migrated rows) as part of this
 *                            reschedule; ignored when an event already
 *                            exists or the Graph toggle is off
 */
public record InterviewScheduleRequest(
        LocalDateTime scheduledAt,
        String location,
        String roomEmail,
        List<String> interviewerUuids,
        Integer durationMinutes,
        Boolean onlineMeeting,
        Boolean createCalendarEvent
) {
}

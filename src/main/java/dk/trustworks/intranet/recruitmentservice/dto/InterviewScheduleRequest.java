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
 * @param scheduledAt      required, wall-clock Europe/Copenhagen as entered
 *                         (the Graph bridge stamps the timezone)
 * @param location         optional replacement location
 * @param roomEmail        optional replacement room mailbox (null = keep,
 *                         blank = clear the booking)
 * @param interviewerUuids optional replacement interviewer list (1–10)
 */
public record InterviewScheduleRequest(
        LocalDateTime scheduledAt,
        String location,
        String roomEmail,
        List<String> interviewerUuids
) {
}

package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reschedule an interview (P11): a new time is mandatory; location and the
 * interviewer list are optional (null = keep current value). Changing
 * interviewers is allowed — a submitted scorecard from a removed
 * interviewer is kept and still counts in the debrief.
 *
 * @param scheduledAt      required, UTC
 * @param location         optional replacement location
 * @param interviewerUuids optional replacement interviewer list (1–10)
 */
public record InterviewScheduleRequest(
        LocalDateTime scheduledAt,
        String location,
        List<String> interviewerUuids
) {
}

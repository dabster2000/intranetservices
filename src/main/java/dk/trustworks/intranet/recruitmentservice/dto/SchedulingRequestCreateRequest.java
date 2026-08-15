package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Start Method B on an application (plan §8.5). Interview parameters
 * mirror {@link InterviewCreateRequest} minus the fixed time — Method B
 * finds the time. All validation is explicit in the resource/service —
 * bean validation is inert in this backend (house rule, findings §P4).
 *
 * @param kind                     ROUND or INFORMAL
 * @param round                    1..3 for ROUND, null for INFORMAL
 * @param interviewerUuids         REQUIRED interviewers (1–10 existing
 *                                 users) — each must approve every option
 * @param optionalInterviewerUuids optional interviewers (≤10) — never
 *                                 block a slot; slots where they are free
 *                                 rank higher (defaults §29.7)
 * @param durationMinutes          15..480; null = 60
 * @param onlineMeeting            the finalized interview becomes Teams
 * @param requireRoom              only slots with a free bookable room
 * @param location                 optional PII-free free-text location
 * @param requestedOptions         1..3 options to secure; null = 3
 * @param windowStart              first date options may fall on
 * @param windowEnd                last date (inclusive), ≤ 60 days later
 * @param permittedStart           earliest wall-clock start (optional)
 * @param permittedEnd             latest wall-clock end (optional)
 * @param minSeparationHours       0..168 between options; null = 0
 * @param differentDays            options on distinct days
 * @param candidateDeadline        explicit answer-by override; null =
 *                                 computed at send time (3 business days)
 * @param automationDays           days until the automation hands back;
 *                                 null = 14 (defaults §29.18)
 * @param reviewRequired           D11 review gate; null = true
 * @param manualCandidateDelivery  recruiter-sends-the-link mode: the
 *                                 send step mails the candidate NOTHING;
 *                                 the recruiter gets the tokenized link
 *                                 + a ready-to-send text draft in Slack.
 *                                 null = false (mail as always)
 */
public record SchedulingRequestCreateRequest(
        RecruitmentInterviewKind kind,
        Integer round,
        List<String> interviewerUuids,
        List<String> optionalInterviewerUuids,
        Integer durationMinutes,
        Boolean onlineMeeting,
        Boolean requireRoom,
        String location,
        Integer requestedOptions,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalTime permittedStart,
        LocalTime permittedEnd,
        Integer minSeparationHours,
        Boolean differentDays,
        LocalDateTime candidateDeadline,
        Integer automationDays,
        Boolean reviewRequired,
        Boolean manualCandidateDelivery
) {
}

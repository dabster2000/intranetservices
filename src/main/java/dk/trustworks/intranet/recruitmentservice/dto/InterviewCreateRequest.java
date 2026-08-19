package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Create (= schedule) an interview on an application (P11). The API always
 * schedules with a time — there is no PLANNED draft state (spec §5.3: the
 * teamlead "schedules Interview 1", one step). All validation is explicit
 * in the resource/service — bean validation is inert in this backend
 * (house rule, findings §P4).
 *
 * @param kind             ROUND, INFORMAL or OFFER
 * @param round            1..3, required for ROUND, must be null for every
 *                         other kind
 * @param interviewerUuids the assigned interviewers (1–10 existing users)
 * @param location         optional, PII-free: room name or "Teams"
 * @param roomEmail        optional room mailbox (Graph places) to book as a
 *                         "resource" attendee on the Outlook event
 * @param scheduledAt      required, wall-clock Europe/Copenhagen as entered
 *                         (the Graph bridge stamps the timezone)
 * @param durationMinutes  optional interview length in minutes (15..480);
 *                         null = the 60-minute default. Drives the Outlook
 *                         event end time and the free/busy windows.
 * @param onlineMeeting    optional: TRUE makes the Outlook event a Teams
 *                         meeting (null/FALSE = plain event). With no room
 *                         and no location given, the location defaults to
 *                         "Microsoft Teams".
 * @param createCalendarEvent optional: FALSE records the interview WITHOUT
 *                         creating any Outlook event (F17 — the interview
 *                         was already booked by hand; only the ATS record
 *                         with scorecards/debrief/stage progression is
 *                         wanted). null/TRUE = create as always. The
 *                         reschedule dialog's existing "create the
 *                         invitation now" checkbox attaches a real event
 *                         later.
 */
public record InterviewCreateRequest(
        RecruitmentInterviewKind kind,
        Integer round,
        List<String> interviewerUuids,
        String location,
        String roomEmail,
        LocalDateTime scheduledAt,
        Integer durationMinutes,
        Boolean onlineMeeting,
        Boolean createCalendarEvent
) {
}

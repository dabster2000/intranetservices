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
 * @param kind             ROUND or INFORMAL
 * @param round            1..3, required for ROUND, must be null for INFORMAL
 * @param interviewerUuids the assigned interviewers (1–10 existing users)
 * @param location         optional, PII-free: room name or "Teams"
 * @param scheduledAt      required, UTC
 */
public record InterviewCreateRequest(
        RecruitmentInterviewKind kind,
        Integer round,
        List<String> interviewerUuids,
        String location,
        LocalDateTime scheduledAt
) {
}

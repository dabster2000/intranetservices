package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope of {@code GET /recruitment/applications/{uuid}/communication-plan}
 * ({@code ICommunicationPlan} in the FE↔BE contract): what a pending pipeline
 * action would send to the candidate (and who is copied), computed from the
 * live configuration — templates, flags, copy policy — by
 * {@code RecruitmentCommunicationPlanService}. Read-only and side-effect free;
 * the action dialogs render it as the "what will the candidate receive" strip
 * and popover.
 *
 * <p>Every enum-like field travels as the server enum's {@code name()} so the
 * FE union types mirror the planner's vocabulary exactly.</p>
 */
public record CommunicationPlanResponse(
        String action,
        List<PlanStep> steps,
        PlanSummary summary
) {

    /**
     * One communication the action would (or deliberately would not)
     * produce, in delivery order.
     *
     * @param channel      EMAIL | CALENDAR | SLACK | LINK
     * @param audience     CANDIDATE | INTERVIEWERS | RECRUITER
     * @param outcome      SENDS | QUEUED_FOR_REVIEW | SKIPPED
     * @param timing       IMMEDIATE | AFTER_APPROVAL | AFTER_CANDIDATE_CHOICE | MANUAL
     * @param reason       machine code explaining a SKIPPED/QUEUED outcome
     *                     (e.g. NO_ACTIVE_TEMPLATE, PARTNER_REFERRAL,
     *                     BACK_MOVE, NO_CANDIDATE_EMAIL, CALENDAR_DISABLED,
     *                     FLAG_OFF, MANUAL_DELIVERY, NO_COMMUNICATION); null
     *                     on a plain send
     * @param templateKey  the template the step is driven by; null when none
     * @param templateName the template's display name; null when none
     * @param subject      the template's raw subject line (merge fields
     *                     unresolved); null when none
     * @param copies       who is copied on the email; null when the step
     *                     carries no copy policy
     */
    public record PlanStep(
            int order,
            String channel,
            String audience,
            String outcome,
            String timing,
            String reason,
            String templateKey,
            String templateName,
            String subject,
            CopyPreview copies
    ) {
    }

    /**
     * The copy policy applied to one email step. {@code recipients} is the
     * resolved, authorization-filtered list — populated only for callers on
     * the candidate-email tier; below it, {@code roles} alone describes the
     * policy.
     */
    public record CopyPreview(
            String mode,
            List<String> roles,
            List<CopyPerson> recipients
    ) {
    }

    /** One resolved copy recipient (an employee, never the candidate). */
    public record CopyPerson(
            String userUuid,
            String name,
            String email
    ) {
    }

    /** The strip's at-a-glance answer, derived from the steps. */
    public record PlanSummary(
            boolean emailsCandidate,
            boolean calendarInvite,
            boolean requiresApproval,
            boolean nothingSent,
            int copyCount
    ) {
    }
}

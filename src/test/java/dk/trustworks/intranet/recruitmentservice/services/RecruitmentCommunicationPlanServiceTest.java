package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse.PlanStep;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyMode;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.PlanAction;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.PlanContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.AUDIENCE_CANDIDATE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.AUDIENCE_INTERVIEWERS;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.AUDIENCE_RECRUITER;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.CHANNEL_CALENDAR;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.CHANNEL_EMAIL;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.CHANNEL_LINK;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.CHANNEL_SLACK;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.OUTCOME_QUEUED_FOR_REVIEW;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.OUTCOME_SENDS;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.OUTCOME_SKIPPED;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_BACK_MOVE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_CALENDAR_DISABLED;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_FLAG_OFF;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_MANUAL_DELIVERY;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_NO_CANDIDATE_EMAIL;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_NO_COMMUNICATION;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_NO_TEMPLATE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_PARTNER_REFERRAL;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_SENDER_OPTED_OUT;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.REASON_TEMPLATE_INACTIVE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.TIMING_AFTER_APPROVAL;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.TIMING_AFTER_CANDIDATE_CHOICE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.TIMING_ON_STAGE_ENTRY;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommunicationPlanService.buildPlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The communication-plan core (fast tier, no DB): every branch must judge a
 * pending action exactly as the reactors judge the real one —
 * {@code CandidateMailerReactor} for template emails,
 * {@code RecruitmentCalendarService} for the two-event split,
 * {@code RecruitmentSchedulingCandidateService} for Method B. When a rule
 * moves in one of those, the matching case here must move with it.
 */
class RecruitmentCommunicationPlanServiceTest {

    private static final List<String> STAGE_SET = List.of(
            "SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED");

    private static RecruitmentEmailTemplate template(String key, boolean active,
                                                     boolean autoSend, String copyRoles) {
        RecruitmentEmailTemplate t = new RecruitmentEmailTemplate();
        t.setUuid("uuid-" + key);
        t.setTemplateKey(key);
        t.setName("Skabelon " + key);
        t.setSubject("Emne for " + key);
        t.setBody("Kære {{candidate_first_name}}");
        t.setActive(active);
        t.setAutoSend(autoSend);
        t.setCopyRoles(copyRoles == null ? "" : copyRoles);
        t.setCopyMode(RecruitmentEmailCopyMode.BCC);
        return t;
    }

    private static PlanContext context(PlanAction action, RecruitmentStage from,
                                       RecruitmentStage to, Integer round,
                                       boolean candidateHasEmail, boolean partnerReferral,
                                       boolean interviewsEnabled, boolean calendarEnabled,
                                       boolean methodBEnabled, boolean manualDelivery,
                                       boolean reviewRequired,
                                       Map<String, RecruitmentEmailTemplate> templates) {
        Function<String, RecruitmentEmailTemplate> lookup = templates::get;
        return new PlanContext(action, from, to, round, STAGE_SET, candidateHasEmail,
                partnerReferral, interviewsEnabled, calendarEnabled, methodBEnabled,
                manualDelivery, reviewRequired, true, lookup,
                t -> RecruitmentEmailService.EmailCopies.none());
    }

    /** A REJECT context carrying the dialog's live choices. */
    private static PlanContext rejectContext(RecruitmentStage from,
                                             RecruitmentRejectionReason reasonCode,
                                             String override, boolean suppress,
                                             Map<String, RecruitmentEmailTemplate> templates) {
        return new PlanContext(PlanAction.REJECT, from, null, null, STAGE_SET,
                true, false, true, true, false, false, false, true, templates::get,
                t -> RecruitmentEmailService.EmailCopies.none(),
                reasonCode, override, suppress);
    }

    private static PlanStep only(CommunicationPlanResponse plan) {
        assertEquals(1, plan.steps().size(), "expected exactly one step");
        return plan.steps().get(0);
    }

    // ---- Stage moves ----------------------------------------------------

    @Test
    void forwardStageMoveWithAutoSendTemplateSends() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null,
                true, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, true, ""))));
        PlanStep step = only(plan);
        assertEquals(OUTCOME_SENDS, step.outcome());
        assertEquals(CHANNEL_EMAIL, step.channel());
        assertEquals(AUDIENCE_CANDIDATE, step.audience());
        assertEquals("Skabelon STAGE_INTERVIEW_1", step.templateName());
        assertTrue(plan.summary().emailsCandidate());
        assertFalse(plan.summary().requiresApproval());
    }

    @Test
    void forwardStageMoveWithReviewFirstTemplateQueues() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null,
                true, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, false, ""))));
        PlanStep step = only(plan);
        assertEquals(OUTCOME_QUEUED_FOR_REVIEW, step.outcome());
        assertEquals(TIMING_AFTER_APPROVAL, step.timing());
        assertTrue(plan.summary().requiresApproval());
    }

    @Test
    void backMoveNeverEmails() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.INTERVIEW_2, RecruitmentStage.INTERVIEW_1, null,
                true, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, true, ""))));
        PlanStep step = only(plan);
        assertEquals(OUTCOME_SKIPPED, step.outcome());
        assertEquals(REASON_BACK_MOVE, step.reason());
        assertTrue(plan.summary().nothingSent());
    }

    @Test
    void stageMoveDistinguishesMissingFromInactiveTemplate() {
        CommunicationPlanResponse missing = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null,
                true, false, true, true, false, false, false, Map.of()));
        assertEquals(REASON_NO_TEMPLATE, only(missing).reason());

        CommunicationPlanResponse inactive = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null,
                true, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", false, true, ""))));
        assertEquals(REASON_TEMPLATE_INACTIVE, only(inactive).reason());
        assertEquals("Skabelon STAGE_INTERVIEW_1", only(inactive).templateName());
    }

    @Test
    void stageMoveSkipsWhenFlagOffOrNoEmail() {
        CommunicationPlanResponse flagOff = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null,
                true, false, false, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, true, ""))));
        assertEquals(REASON_FLAG_OFF, only(flagOff).reason());

        CommunicationPlanResponse noEmail = buildPlan(context(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null,
                false, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, true, ""))));
        assertEquals(REASON_NO_CANDIDATE_EMAIL, only(noEmail).reason());
    }

    // ---- Rejection ------------------------------------------------------

    @Test
    void rejectFromScreeningUsesScreeningTemplate() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.REJECT,
                RecruitmentStage.SCREENING, null, null,
                true, false, true, true, false, false, false,
                Map.of("REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        assertEquals("REJECTION_SCREENING", only(plan).templateKey());
        assertEquals(OUTCOME_SENDS, only(plan).outcome());
    }

    @Test
    void rejectAfterInterviewUsesPostInterviewTemplate() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.REJECT,
                RecruitmentStage.INTERVIEW_2, null, null,
                true, false, true, true, false, false, false,
                Map.of("REJECTION_POST_INTERVIEW",
                        template("REJECTION_POST_INTERVIEW", true, true, ""))));
        assertEquals("REJECTION_POST_INTERVIEW", only(plan).templateKey());
    }

    @Test
    void partnerReferralRejectionNeverAutoSends() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.REJECT,
                RecruitmentStage.SCREENING, null, null,
                true, true, true, true, false, false, false,
                Map.of("REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        PlanStep step = only(plan);
        assertEquals(OUTCOME_QUEUED_FOR_REVIEW, step.outcome());
        assertEquals(REASON_PARTNER_REFERRAL, step.reason());
    }

    // ---- Silent actions -------------------------------------------------

    @Test
    void withdrawSendsNothing() {
        // The candidate backed out — there is no trigger and nothing to say.
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.WITHDRAW,
                RecruitmentStage.INTERVIEW_1, null, null,
                true, false, true, true, false, false, false, Map.of()));
        assertEquals(REASON_NO_COMMUNICATION, only(plan).reason());
        assertTrue(plan.summary().nothingSent());
    }

    // ---- Return to pool (2026-09-02: no longer silent) -------------------

    @Test
    void returnToPoolPrefersTheSilverMedalistLetter() {
        // A candidate who lost to another hire is pooled as SILVER_MEDALIST,
        // and deserves different words from a cold prospect — which is the
        // whole point of the bucket rung sitting above the generic one.
        CommunicationPlanResponse plan = buildPlan(rejectContextlessReturnToPool(Map.of(
                "POOLED_SILVER_MEDALIST",
                template("POOLED_SILVER_MEDALIST", true, true, ""),
                "POOLED", template("POOLED", true, true, ""))));
        assertEquals("POOLED_SILVER_MEDALIST", only(plan).templateKey());
        assertEquals(OUTCOME_SENDS, only(plan).outcome());
    }

    @Test
    void returnToPoolFallsBackToTheGenericPoolLetter() {
        CommunicationPlanResponse plan = buildPlan(rejectContextlessReturnToPool(
                Map.of("POOLED", template("POOLED", true, false, ""))));
        assertEquals("POOLED", only(plan).templateKey());
        assertEquals(OUTCOME_QUEUED_FOR_REVIEW, only(plan).outcome());
    }

    @Test
    void returnToPoolWithNoPoolLetterNamesTheGenericKeyToConfigure() {
        // Nothing sendable: the step must name the rung TA should create,
        // not the specific one they have never heard of.
        CommunicationPlanResponse plan =
                buildPlan(rejectContextlessReturnToPool(Map.of()));
        assertEquals(REASON_NO_TEMPLATE, only(plan).reason());
        assertEquals("POOLED", only(plan).templateKey());
        assertTrue(plan.summary().nothingSent());
    }

    private static PlanContext rejectContextlessReturnToPool(
            Map<String, RecruitmentEmailTemplate> templates) {
        return context(PlanAction.RETURN_TO_POOL, RecruitmentStage.INTERVIEW_1, null, null,
                true, false, true, true, false, false, false, templates);
    }

    // ---- Rejection routing on the reason code (2026-09-02) ---------------

    @Test
    void reasonAndStageTemplateWinsOverReasonAloneAndOverGeneric() {
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.EXPERIENCE_LEVEL,
                null, false, Map.of(
                        "REJECTION_EXPERIENCE_LEVEL_SCREENING",
                        template("REJECTION_EXPERIENCE_LEVEL_SCREENING", true, true, ""),
                        "REJECTION_EXPERIENCE_LEVEL",
                        template("REJECTION_EXPERIENCE_LEVEL", true, true, ""),
                        "REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        assertEquals("REJECTION_EXPERIENCE_LEVEL_SCREENING", only(plan).templateKey());
    }

    @Test
    void reasonTemplateWinsWhenNoStageSpecificOneExists() {
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.LOCATION_LANGUAGE,
                null, false, Map.of(
                        "REJECTION_LOCATION_LANGUAGE",
                        template("REJECTION_LOCATION_LANGUAGE", true, true, ""),
                        "REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        assertEquals("REJECTION_LOCATION_LANGUAGE", only(plan).templateKey());
    }

    @Test
    void anInactiveSpecificLetterFallsThroughToTheGenericOne() {
        // Deactivating a specific letter must resume the generic one rather
        // than silence the rejection — "off" means "not this letter", never
        // "no letter".
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.EXPERIENCE_LEVEL,
                null, false, Map.of(
                        "REJECTION_EXPERIENCE_LEVEL",
                        template("REJECTION_EXPERIENCE_LEVEL", false, true, ""),
                        "REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        assertEquals("REJECTION_SCREENING", only(plan).templateKey());
        assertEquals(OUTCOME_SENDS, only(plan).outcome());
    }

    @Test
    void withNoLetterAtAllTheInactiveRowIsWhatGetsReported() {
        // The actionable fact is "someone switched this off", not "the key
        // you have never seen is missing".
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.EXPERIENCE_LEVEL,
                null, false, Map.of(
                        "REJECTION_SCREENING", template("REJECTION_SCREENING", false, true, ""))));
        assertEquals(REASON_TEMPLATE_INACTIVE, only(plan).reason());
        assertEquals("REJECTION_SCREENING", only(plan).templateKey());
    }

    @Test
    void aReasonlessPlanStillAnswersWithTheGenericLetter() {
        // The dialog asks for a plan before a reason is picked.
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.INTERVIEW_2, null, null, false,
                Map.of("REJECTION_POST_INTERVIEW",
                        template("REJECTION_POST_INTERVIEW", true, true, ""))));
        assertEquals("REJECTION_POST_INTERVIEW", only(plan).templateKey());
    }

    @Test
    void anExplicitTemplateChoiceOverridesTheChain() {
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.EXPERIENCE_LEVEL,
                "REJECTION_CULTURE_FIT", false, Map.of(
                        "REJECTION_EXPERIENCE_LEVEL",
                        template("REJECTION_EXPERIENCE_LEVEL", true, true, ""),
                        "REJECTION_CULTURE_FIT",
                        template("REJECTION_CULTURE_FIT", true, true, ""))));
        assertEquals("REJECTION_CULTURE_FIT", only(plan).templateKey());
    }

    @Test
    void anOverrideThatCannotSendDoesNotSilentlyPickAnotherLetter() {
        // A recruiter who chose a letter and got a different one would never
        // know. Report the choice as unsendable instead.
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.EXPERIENCE_LEVEL,
                "REJECTION_CULTURE_FIT", false, Map.of(
                        "REJECTION_CULTURE_FIT",
                        template("REJECTION_CULTURE_FIT", false, true, ""),
                        "REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        assertEquals(REASON_TEMPLATE_INACTIVE, only(plan).reason());
        assertEquals("REJECTION_CULTURE_FIT", only(plan).templateKey());
    }

    @Test
    void optingOutOfTheEmailSendsNothingAndSaysSo() {
        CommunicationPlanResponse plan = buildPlan(rejectContext(
                RecruitmentStage.SCREENING, RecruitmentRejectionReason.OTHER, null, true,
                Map.of("REJECTION_SCREENING", template("REJECTION_SCREENING", true, true, ""))));
        assertEquals(REASON_SENDER_OPTED_OUT, only(plan).reason());
        assertTrue(plan.summary().nothingSent());
    }

    // ---- Interview scheduling (Method A) --------------------------------

    @Test
    void interviewScheduleSendsBothCalendarInvites() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_SCHEDULE,
                RecruitmentStage.INTERVIEW_1, null, 1,
                true, false, true, true, false, false, false,
                Map.of("INTERVIEW_CANDIDATE_INVITATION",
                        template("INTERVIEW_CANDIDATE_INVITATION", true, true, ""))));
        assertEquals(2, plan.steps().size());
        PlanStep candidate = plan.steps().get(0);
        assertEquals(CHANNEL_CALENDAR, candidate.channel());
        assertEquals(AUDIENCE_CANDIDATE, candidate.audience());
        assertEquals(OUTCOME_SENDS, candidate.outcome());
        assertEquals("INTERVIEW_CANDIDATE_INVITATION", candidate.templateKey());
        PlanStep interviewers = plan.steps().get(1);
        assertEquals(AUDIENCE_INTERVIEWERS, interviewers.audience());
        assertTrue(plan.summary().calendarInvite());
    }

    @Test
    void interviewScheduleForFutureRoundAddsStageEntryEmail() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_SCHEDULE,
                RecruitmentStage.SCREENING, null, 1,
                true, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, true, ""))));
        assertEquals(3, plan.steps().size());
        PlanStep stageMail = plan.steps().get(2);
        assertEquals(CHANNEL_EMAIL, stageMail.channel());
        assertEquals(TIMING_ON_STAGE_ENTRY, stageMail.timing());
        assertEquals("STAGE_INTERVIEW_1", stageMail.templateKey());
    }

    @Test
    void interviewScheduleAtOrPastRoundStageOmitsStageEntryEmail() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_SCHEDULE,
                RecruitmentStage.INTERVIEW_1, null, 1,
                true, false, true, true, false, false, false,
                Map.of("STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, true, ""))));
        assertEquals(2, plan.steps().size());
    }

    @Test
    void interviewScheduleWithoutStageTemplateStaysSilentAboutIt() {
        // Remediation F5: interview stages deliberately have no stage
        // template — the plan must not advertise a "missing" one.
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_SCHEDULE,
                RecruitmentStage.SCREENING, null, 1,
                true, false, true, true, false, false, false, Map.of()));
        assertEquals(2, plan.steps().size());
        assertTrue(plan.steps().stream().noneMatch(s -> CHANNEL_EMAIL.equals(s.channel())));
    }

    @Test
    void interviewScheduleWithCalendarDisabledSkipsWithReason() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_SCHEDULE,
                RecruitmentStage.INTERVIEW_1, null, 1,
                true, false, true, false, false, false, false, Map.of()));
        assertEquals(REASON_CALENDAR_DISABLED, plan.steps().get(0).reason());
        assertEquals(REASON_CALENDAR_DISABLED, plan.steps().get(1).reason());
        assertFalse(plan.summary().calendarInvite());
    }

    @Test
    void interviewScheduleWithoutCandidateEmailSkipsCandidateHalfOnly() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_SCHEDULE,
                RecruitmentStage.INTERVIEW_1, null, 1,
                false, false, true, true, false, false, false, Map.of()));
        assertEquals(REASON_NO_CANDIDATE_EMAIL, plan.steps().get(0).reason());
        assertEquals(OUTCOME_SENDS, plan.steps().get(1).outcome());
    }

    // ---- Method B -------------------------------------------------------

    @Test
    void methodBAutoDeliveryPlansSlackThenOptionsThenCalendar() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.METHOD_B_START,
                RecruitmentStage.SCREENING, null, 1,
                true, false, true, true, true, false, false,
                Map.of("OPTION_INVITATION", template("OPTION_INVITATION", true, true, ""),
                        "STAGE_INTERVIEW_1", template("STAGE_INTERVIEW_1", true, false, ""))));
        assertEquals(5, plan.steps().size());
        assertEquals(CHANNEL_SLACK, plan.steps().get(0).channel());
        assertEquals(AUDIENCE_INTERVIEWERS, plan.steps().get(0).audience());
        PlanStep options = plan.steps().get(1);
        assertEquals(CHANNEL_EMAIL, options.channel());
        assertEquals(OUTCOME_SENDS, options.outcome());
        assertEquals("OPTION_INVITATION", options.templateKey());
        assertEquals(TIMING_AFTER_CANDIDATE_CHOICE, plan.steps().get(2).timing());
        assertEquals(CHANNEL_CALENDAR, plan.steps().get(2).channel());
        // The round's stage still lies ahead → the stage-entry email rides
        // along, judged by ITS template (review-first here).
        assertEquals(OUTCOME_QUEUED_FOR_REVIEW, plan.steps().get(4).outcome());
    }

    @Test
    void methodBReviewRequiredQueuesOptions() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.METHOD_B_START,
                RecruitmentStage.INTERVIEW_1, null, 1,
                true, false, true, true, true, false, true,
                Map.of("OPTION_INVITATION", template("OPTION_INVITATION", true, true, ""))));
        PlanStep options = plan.steps().get(1);
        assertEquals(OUTCOME_QUEUED_FOR_REVIEW, options.outcome());
        assertEquals(TIMING_AFTER_APPROVAL, options.timing());
    }

    @Test
    void methodBManualDeliveryHandsLinkToRecruiter() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.METHOD_B_START,
                RecruitmentStage.INTERVIEW_1, null, 1,
                false, false, true, true, true, true, false, Map.of()));
        PlanStep link = plan.steps().get(1);
        assertEquals(CHANNEL_LINK, link.channel());
        assertEquals(AUDIENCE_RECRUITER, link.audience());
        assertEquals(REASON_MANUAL_DELIVERY, link.reason());
        assertFalse(plan.summary().emailsCandidate());
    }

    @Test
    void methodBWithoutOptionTemplateSurfacesTheStall() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.METHOD_B_START,
                RecruitmentStage.INTERVIEW_1, null, 1,
                true, false, true, true, true, false, false, Map.of()));
        PlanStep options = plan.steps().get(1);
        assertEquals(OUTCOME_SKIPPED, options.outcome());
        assertEquals(REASON_NO_TEMPLATE, options.reason());
        assertEquals("OPTION_INVITATION", options.templateKey());
    }

    @Test
    void methodBFlagOffCollapsesToSingleSkip() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.METHOD_B_START,
                RecruitmentStage.INTERVIEW_1, null, 1,
                true, false, true, true, false, false, false, Map.of()));
        assertEquals(REASON_FLAG_OFF, only(plan).reason());
    }

    // ---- Reschedule / cancel -------------------------------------------

    @Test
    void rescheduleUpdatesBothInvitations() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_RESCHEDULE,
                RecruitmentStage.INTERVIEW_1, null, null,
                true, false, true, true, false, false, false, Map.of()));
        assertEquals(2, plan.steps().size());
        assertEquals(OUTCOME_SENDS, plan.steps().get(0).outcome());
        assertEquals(AUDIENCE_CANDIDATE, plan.steps().get(0).audience());
    }

    @Test
    void cancelWithCalendarDisabledSkips() {
        CommunicationPlanResponse plan = buildPlan(context(PlanAction.INTERVIEW_CANCEL,
                RecruitmentStage.INTERVIEW_1, null, null,
                true, false, true, false, false, false, false, Map.of()));
        assertEquals(REASON_CALENDAR_DISABLED, only(plan).reason());
        assertNull(only(plan).templateKey());
    }

    // ---- Copy preview ---------------------------------------------------

    @Test
    void copyRolesTravelWithoutNamesBelowEmailTier() {
        RecruitmentEmailTemplate withCopies =
                template("STAGE_INTERVIEW_1", true, true, "INTERVIEWERS,HIRING_OWNER");
        Function<String, RecruitmentEmailTemplate> lookup =
                Map.of("STAGE_INTERVIEW_1", withCopies)::get;
        PlanContext ctx = new PlanContext(PlanAction.STAGE_MOVE,
                RecruitmentStage.SCREENING, RecruitmentStage.INTERVIEW_1, null, STAGE_SET,
                true, false, true, true, false, false, false, false, lookup,
                t -> RecruitmentEmailService.EmailCopies.none());
        CommunicationPlanResponse plan = buildPlan(ctx);
        PlanStep step = only(plan);
        assertEquals(List.of("HIRING_OWNER", "INTERVIEWERS"), step.copies().roles());
        assertTrue(step.copies().recipients().isEmpty());
        assertEquals("BCC", step.copies().mode());
    }
}

package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse.CopyPerson;
import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse.CopyPreview;
import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse.PlanStep;
import dk.trustworks.intranet.recruitmentservice.dto.CommunicationPlanResponse.PlanSummary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Computes the {@code communication-plan} read model (2026-08-26): what a
 * pending pipeline action would send to the candidate, judged against the
 * LIVE configuration — the exact rules {@link
 * dk.trustworks.intranet.recruitmentservice.notifications.CandidateMailerReactor},
 * {@link RecruitmentCalendarService} and {@link
 * RecruitmentSchedulingCandidateService} apply when the action later runs.
 * Read-only and side-effect free; feeds the action dialogs' "what will the
 * candidate receive" strip.
 *
 * <p>The decision logic lives in the static {@link #buildPlan(PlanContext)}
 * core over a plain {@link PlanContext}, so the fast (DB-free) test tier can
 * exercise every branch without a database; this bean only gathers the
 * context. Keep the core in lockstep with the reactors — it PREDICTS them,
 * it does not drive them.</p>
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCommunicationPlanService {

    /** The pipeline actions a plan can be asked for. */
    public enum PlanAction {
        STAGE_MOVE,
        REJECT,
        WITHDRAW,
        RETURN_TO_POOL,
        INTERVIEW_SCHEDULE,
        INTERVIEW_RESCHEDULE,
        INTERVIEW_CANCEL,
        METHOD_B_START
    }

    // Step vocabulary — string constants rather than enums because they only
    // exist to travel: the FE union types mirror them 1:1.
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_CALENDAR = "CALENDAR";
    public static final String CHANNEL_SLACK = "SLACK";
    public static final String CHANNEL_LINK = "LINK";

    public static final String AUDIENCE_CANDIDATE = "CANDIDATE";
    public static final String AUDIENCE_INTERVIEWERS = "INTERVIEWERS";
    public static final String AUDIENCE_RECRUITER = "RECRUITER";

    public static final String OUTCOME_SENDS = "SENDS";
    public static final String OUTCOME_QUEUED_FOR_REVIEW = "QUEUED_FOR_REVIEW";
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    public static final String TIMING_IMMEDIATE = "IMMEDIATE";
    public static final String TIMING_AFTER_APPROVAL = "AFTER_APPROVAL";
    public static final String TIMING_AFTER_CANDIDATE_CHOICE = "AFTER_CANDIDATE_CHOICE";
    public static final String TIMING_ON_STAGE_ENTRY = "ON_STAGE_ENTRY";
    public static final String TIMING_MANUAL = "MANUAL";

    public static final String REASON_NO_TEMPLATE = "NO_TEMPLATE";
    public static final String REASON_TEMPLATE_INACTIVE = "TEMPLATE_INACTIVE";
    public static final String REASON_NO_CANDIDATE_EMAIL = "NO_CANDIDATE_EMAIL";
    public static final String REASON_PARTNER_REFERRAL = "PARTNER_REFERRAL";
    public static final String REASON_BACK_MOVE = "BACK_MOVE";
    public static final String REASON_CALENDAR_DISABLED = "CALENDAR_DISABLED";
    public static final String REASON_FLAG_OFF = "FLAG_OFF";
    public static final String REASON_MANUAL_DELIVERY = "MANUAL_DELIVERY";
    public static final String REASON_NO_COMMUNICATION = "NO_COMMUNICATION";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSchedulingFeatureFlag schedulingFlag;

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    RecruitmentEmailService emailService;

    /**
     * Everything the pure core needs, gathered up front. {@code stageSet}
     * is the position's ordered stage codes (never empty — the position
     * fallback applies before construction). The two functions are the DB
     * boundary: {@code templateByKey} answers the template row for a key in
     * ANY state (null = never configured), {@code copiesFor} resolves the
     * template's copy policy against this candidate.
     *
     * @param round      the interview round for INTERVIEW_SCHEDULE /
     *                   METHOD_B_START, 1–3; null for INFORMAL/OFFER kinds
     * @param toStage    the target stage for STAGE_MOVE; null otherwise
     * @param includeCopyNames whether resolved recipient names may travel
     *                   (candidate-email tier); below it, roles only
     */
    public record PlanContext(
            PlanAction action,
            RecruitmentStage fromStage,
            RecruitmentStage toStage,
            Integer round,
            List<String> stageSet,
            boolean candidateHasEmail,
            boolean partnerReferral,
            boolean interviewsEnabled,
            boolean calendarEnabled,
            boolean methodBEnabled,
            boolean manualDelivery,
            boolean reviewRequired,
            boolean includeCopyNames,
            Function<String, RecruitmentEmailTemplate> templateByKey,
            Function<RecruitmentEmailTemplate, RecruitmentEmailService.EmailCopies> copiesFor
    ) {
    }

    /**
     * The bean entry point: gather live flags and entity facts, delegate to
     * the core. {@code position} may carry an empty stage set — the default
     * set substitution mirrors {@link RecruitmentApplicationService}.
     */
    public CommunicationPlanResponse plan(RecruitmentApplication application,
                                          RecruitmentCandidate candidate,
                                          RecruitmentPosition position,
                                          PlanAction action,
                                          RecruitmentStage toStage,
                                          Integer round,
                                          boolean manualDelivery,
                                          boolean reviewRequired,
                                          boolean includeCopyNames) {
        // Fallback: the canonical catalog order — declaration order IS the
        // pipeline order (RecruitmentStage contract), so direction judgments
        // stay right even for a position without a stored stage set.
        List<String> stageSet = position == null || position.getStageSet() == null
                || position.getStageSet().isEmpty()
                ? java.util.Arrays.stream(RecruitmentStage.values()).map(Enum::name).toList()
                : position.getStageSet();
        String applicationUuid = application.getUuid();
        PlanContext context = new PlanContext(
                action,
                application.getStage(),
                toStage,
                round,
                stageSet,
                candidate != null && candidate.getEmail() != null
                        && !candidate.getEmail().isBlank(),
                candidate != null && candidate.getSource() == CandidateSource.PARTNER_REFERRAL,
                featureFlag.isInterviewsEnabled(),
                calendarService.isEnabled(),
                schedulingFlag.isMethodBEnabled(),
                manualDelivery,
                reviewRequired,
                includeCopyNames,
                key -> RecruitmentEmailTemplate
                        .<RecruitmentEmailTemplate>find("templateKey", key).firstResult(),
                template -> emailService.copiesFor(template, candidate, applicationUuid, null));
        return buildPlan(context);
    }

    // ------------------------------------------------------------------
    // Pure core — no CDI, no DB; every rule mirrors a reactor's rule and
    // says so. Tested branch-by-branch in the fast tier.
    // ------------------------------------------------------------------

    public static CommunicationPlanResponse buildPlan(PlanContext ctx) {
        List<PlanStep> steps = switch (ctx.action()) {
            case STAGE_MOVE -> stageMoveSteps(ctx);
            case REJECT -> rejectSteps(ctx);
            case WITHDRAW, RETURN_TO_POOL -> nothingSteps();
            case INTERVIEW_SCHEDULE -> interviewSteps(ctx, TIMING_IMMEDIATE);
            case INTERVIEW_RESCHEDULE -> interviewUpdateSteps(ctx, false);
            case INTERVIEW_CANCEL -> interviewUpdateSteps(ctx, true);
            case METHOD_B_START -> methodBSteps(ctx);
        };
        return new CommunicationPlanResponse(ctx.action().name(), steps, summarize(steps));
    }

    /** CandidateMailerReactor: forward entries fire STAGE_&lt;to&gt;; back-moves never mail. */
    private static List<PlanStep> stageMoveSteps(PlanContext ctx) {
        if (ctx.toStage() == null) {
            return nothingSteps();
        }
        int fromIndex = ctx.stageSet().indexOf(ctx.fromStage() == null ? "" : ctx.fromStage().name());
        int toIndex = ctx.stageSet().indexOf(ctx.toStage().name());
        boolean backMove = fromIndex >= 0 && toIndex >= 0 && toIndex <= fromIndex;
        if (backMove) {
            return List.of(skippedEmail(1, REASON_BACK_MOVE, null, null, null));
        }
        String key = RecruitmentEmailService.STAGE_KEY_PREFIX + ctx.toStage().name();
        return List.of(templateEmailStep(ctx, 1, key, false, TIMING_IMMEDIATE));
    }

    /** CandidateMailerReactor: rejection key splits on the FROM stage; partner referrals never auto-send. */
    private static List<PlanStep> rejectSteps(PlanContext ctx) {
        String key = ctx.fromStage() == RecruitmentStage.SCREENING
                ? RecruitmentEmailService.KEY_REJECTION_SCREENING
                : RecruitmentEmailService.KEY_REJECTION_POST_INTERVIEW;
        return List.of(templateEmailStep(ctx, 1, key, ctx.partnerReferral(), TIMING_IMMEDIATE));
    }

    /** Withdraw / return-to-pool: the mailer has no trigger — nothing goes out. */
    private static List<PlanStep> nothingSteps() {
        return List.of(new PlanStep(1, CHANNEL_EMAIL, AUDIENCE_CANDIDATE, OUTCOME_SKIPPED,
                TIMING_IMMEDIATE, REASON_NO_COMMUNICATION, null, null, null, null));
    }

    /**
     * Method A create (also Method B's finalization leg): the two-event
     * calendar split, then — when the round's stage still lies ahead AND a
     * stage template would actually fire — the conditional stage-template
     * email the accompanying stage move sends. A missing/inactive interview
     * stage template is the DESIGNED state (remediation F5: the candidate's
     * own Outlook invitation is the only interview-stage message), so its
     * absence is silence here, never a "configure one" skip step.
     */
    private static List<PlanStep> interviewSteps(PlanContext ctx, String calendarTiming) {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(candidateCalendarStep(ctx, steps.size() + 1, calendarTiming));
        steps.add(new PlanStep(steps.size() + 1, CHANNEL_CALENDAR, AUDIENCE_INTERVIEWERS,
                ctx.calendarEnabled() ? OUTCOME_SENDS : OUTCOME_SKIPPED, calendarTiming,
                ctx.calendarEnabled() ? null : REASON_CALENDAR_DISABLED,
                null, null, null, null));
        RecruitmentStage roundStage = stageOfRound(ctx.round());
        if (roundStage != null && liesAhead(ctx, roundStage)) {
            String key = RecruitmentEmailService.STAGE_KEY_PREFIX + roundStage.name();
            PlanStep template = templateEmailStep(ctx, steps.size() + 1, key, false,
                    TIMING_ON_STAGE_ENTRY);
            if (!OUTCOME_SKIPPED.equals(template.outcome())) {
                steps.add(template);
            }
        }
        return List.copyOf(steps);
    }

    /** Reschedule / cancel: the existing invitations are updated or cancelled. */
    private static List<PlanStep> interviewUpdateSteps(PlanContext ctx, boolean cancel) {
        String timing = TIMING_IMMEDIATE;
        List<PlanStep> steps = new ArrayList<>();
        if (!ctx.calendarEnabled()) {
            steps.add(new PlanStep(1, CHANNEL_CALENDAR, AUDIENCE_CANDIDATE, OUTCOME_SKIPPED,
                    timing, REASON_CALENDAR_DISABLED, null, null, null, null));
            return List.copyOf(steps);
        }
        steps.add(new PlanStep(1, CHANNEL_CALENDAR, AUDIENCE_CANDIDATE,
                ctx.candidateHasEmail() ? OUTCOME_SENDS : OUTCOME_SKIPPED, timing,
                ctx.candidateHasEmail() ? null : REASON_NO_CANDIDATE_EMAIL,
                cancel ? null : templateKeyOrNull(ctx),
                cancel ? null : templateNameOrNull(ctx),
                null, null));
        steps.add(new PlanStep(2, CHANNEL_CALENDAR, AUDIENCE_INTERVIEWERS, OUTCOME_SENDS,
                timing, null, null, null, null, null));
        return List.copyOf(steps);
    }

    /**
     * Method B start (RecruitmentSchedulingCandidateService): interviewer
     * Slack proposals first — the candidate hears nothing until options are
     * secured — then the OPTION_INVITATION mail (or the recruiter's manual
     * link), then the ordinary create chain once the candidate chooses.
     */
    private static List<PlanStep> methodBSteps(PlanContext ctx) {
        if (!ctx.methodBEnabled()) {
            return List.of(new PlanStep(1, CHANNEL_EMAIL, AUDIENCE_CANDIDATE, OUTCOME_SKIPPED,
                    TIMING_IMMEDIATE, REASON_FLAG_OFF, null, null, null, null));
        }
        List<PlanStep> steps = new ArrayList<>();
        steps.add(new PlanStep(1, CHANNEL_SLACK, AUDIENCE_INTERVIEWERS, OUTCOME_SENDS,
                TIMING_IMMEDIATE, null, null, null, null, null));
        if (ctx.manualDelivery()) {
            steps.add(new PlanStep(2, CHANNEL_LINK, AUDIENCE_RECRUITER, OUTCOME_SENDS,
                    TIMING_MANUAL, REASON_MANUAL_DELIVERY, null, null, null, null));
        } else if (!ctx.candidateHasEmail()) {
            steps.add(skippedEmail(2, REASON_NO_CANDIDATE_EMAIL, null, null, null));
        } else {
            RecruitmentEmailTemplate template = ctx.templateByKey()
                    .apply(RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION);
            if (template == null || !template.isActive()) {
                steps.add(skippedEmail(2,
                        template == null ? REASON_NO_TEMPLATE : REASON_TEMPLATE_INACTIVE,
                        RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION,
                        template == null ? null : template.getName(),
                        template == null ? null : template.getSubject()));
            } else {
                steps.add(new PlanStep(2, CHANNEL_EMAIL, AUDIENCE_CANDIDATE,
                        ctx.reviewRequired() ? OUTCOME_QUEUED_FOR_REVIEW : OUTCOME_SENDS,
                        ctx.reviewRequired() ? TIMING_AFTER_APPROVAL : TIMING_IMMEDIATE,
                        null, template.getTemplateKey(), template.getName(),
                        template.getSubject(), copyPreview(ctx, template)));
            }
        }
        for (PlanStep step : interviewSteps(ctx, TIMING_AFTER_CANDIDATE_CHOICE)) {
            steps.add(new PlanStep(steps.size() + 1, step.channel(), step.audience(),
                    step.outcome(), step.timing(), step.reason(), step.templateKey(),
                    step.templateName(), step.subject(), step.copies()));
        }
        return List.copyOf(steps);
    }

    // ------------------------------------------------------------------
    // Shared step builders
    // ------------------------------------------------------------------

    /**
     * One template-driven candidate email judged exactly as the mailer
     * judges it: flag → address → template row → active → auto-vs-review,
     * with the partner-referral override forcing review.
     */
    private static PlanStep templateEmailStep(PlanContext ctx, int order, String key,
                                              boolean forceReview, String timing) {
        if (!ctx.interviewsEnabled()) {
            return skippedEmail(order, REASON_FLAG_OFF, key, null, null);
        }
        if (!ctx.candidateHasEmail()) {
            return skippedEmail(order, REASON_NO_CANDIDATE_EMAIL, key, null, null);
        }
        RecruitmentEmailTemplate template = ctx.templateByKey().apply(key);
        if (template == null) {
            return skippedEmail(order, REASON_NO_TEMPLATE, key, null, null);
        }
        if (!template.isActive()) {
            return skippedEmail(order, REASON_TEMPLATE_INACTIVE, key,
                    template.getName(), template.getSubject());
        }
        boolean review = forceReview || !template.isAutoSend();
        String reason = forceReview ? REASON_PARTNER_REFERRAL : null;
        return new PlanStep(order, CHANNEL_EMAIL, AUDIENCE_CANDIDATE,
                review ? OUTCOME_QUEUED_FOR_REVIEW : OUTCOME_SENDS,
                review && TIMING_ON_STAGE_ENTRY.equals(timing) ? TIMING_ON_STAGE_ENTRY
                        : review ? TIMING_AFTER_APPROVAL : timing,
                reason, template.getTemplateKey(), template.getName(),
                template.getSubject(), copyPreview(ctx, template));
    }

    /** The candidate's own calendar event, body from INTERVIEW_CANDIDATE_INVITATION. */
    private static PlanStep candidateCalendarStep(PlanContext ctx, int order, String timing) {
        if (!ctx.calendarEnabled()) {
            return new PlanStep(order, CHANNEL_CALENDAR, AUDIENCE_CANDIDATE, OUTCOME_SKIPPED,
                    timing, REASON_CALENDAR_DISABLED, null, null, null, null);
        }
        if (!ctx.candidateHasEmail()) {
            return new PlanStep(order, CHANNEL_CALENDAR, AUDIENCE_CANDIDATE, OUTCOME_SKIPPED,
                    timing, REASON_NO_CANDIDATE_EMAIL, null, null, null, null);
        }
        return new PlanStep(order, CHANNEL_CALENDAR, AUDIENCE_CANDIDATE, OUTCOME_SENDS, timing,
                null, templateKeyOrNull(ctx), templateNameOrNull(ctx), null, null);
    }

    private static PlanStep skippedEmail(int order, String reason, String key,
                                         String templateName, String subject) {
        return new PlanStep(order, CHANNEL_EMAIL, AUDIENCE_CANDIDATE, OUTCOME_SKIPPED,
                TIMING_IMMEDIATE, reason, key, templateName, subject, null);
    }

    /** The invitation template the candidate event renders its body from, if configured. */
    private static String templateKeyOrNull(PlanContext ctx) {
        RecruitmentEmailTemplate template = ctx.templateByKey()
                .apply(RecruitmentEmailService.KEY_INTERVIEW_CANDIDATE_INVITATION);
        return template != null && template.isActive() ? template.getTemplateKey() : null;
    }

    private static String templateNameOrNull(PlanContext ctx) {
        RecruitmentEmailTemplate template = ctx.templateByKey()
                .apply(RecruitmentEmailService.KEY_INTERVIEW_CANDIDATE_INVITATION);
        return template != null && template.isActive() ? template.getName() : null;
    }

    private static CopyPreview copyPreview(PlanContext ctx, RecruitmentEmailTemplate template) {
        List<String> roles = RecruitmentEmailCopyRole.parseCsv(template.getCopyRoles()).stream()
                .map(Enum::name).sorted().toList();
        if (roles.isEmpty()) {
            return null;
        }
        List<CopyPerson> recipients = List.of();
        if (ctx.includeCopyNames()) {
            recipients = ctx.copiesFor().apply(template).recipients().stream()
                    .map(r -> new CopyPerson(r.userUuid(), r.name(), r.email()))
                    .toList();
        }
        return new CopyPreview(template.getCopyMode().name(), roles, recipients);
    }

    private static RecruitmentStage stageOfRound(Integer round) {
        if (round == null) {
            return null;
        }
        return switch (round) {
            case 1 -> RecruitmentStage.INTERVIEW_1;
            case 2 -> RecruitmentStage.INTERVIEW_2;
            case 3 -> RecruitmentStage.INTERVIEW_3;
            default -> null;
        };
    }

    /** Whether the round's stage is still ahead of the application's current stage. */
    private static boolean liesAhead(PlanContext ctx, RecruitmentStage stage) {
        int fromIndex = ctx.stageSet().indexOf(ctx.fromStage() == null ? "" : ctx.fromStage().name());
        int stageIndex = ctx.stageSet().indexOf(stage.name());
        return stageIndex >= 0 && (fromIndex < 0 || stageIndex > fromIndex);
    }

    private static PlanSummary summarize(List<PlanStep> steps) {
        boolean emailsCandidate = steps.stream().anyMatch(s ->
                CHANNEL_EMAIL.equals(s.channel()) && AUDIENCE_CANDIDATE.equals(s.audience())
                        && !OUTCOME_SKIPPED.equals(s.outcome()));
        boolean calendarInvite = steps.stream().anyMatch(s ->
                CHANNEL_CALENDAR.equals(s.channel()) && AUDIENCE_CANDIDATE.equals(s.audience())
                        && OUTCOME_SENDS.equals(s.outcome()));
        boolean requiresApproval = steps.stream()
                .anyMatch(s -> OUTCOME_QUEUED_FOR_REVIEW.equals(s.outcome()));
        boolean nothingSent = steps.stream().allMatch(s -> OUTCOME_SKIPPED.equals(s.outcome()));
        int copyCount = steps.stream()
                .filter(s -> s.copies() != null && !OUTCOME_SKIPPED.equals(s.outcome()))
                .mapToInt(s -> s.copies().recipients().isEmpty()
                        ? s.copies().roles().size() : s.copies().recipients().size())
                .max().orElse(0);
        return new PlanSummary(emailsCandidate, calendarInvite, requiresApproval,
                nothingSent, copyCount);
    }
}

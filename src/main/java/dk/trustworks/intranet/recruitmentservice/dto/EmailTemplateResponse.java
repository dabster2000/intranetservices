package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One candidate-email template (P15). {@code trigger} is true when the
 * key is a reactor-trigger key (ACKNOWLEDGEMENT, REJECTION_*, STAGE_*) —
 * the frontend renders the trigger explainer from it. {@code systemOwned}
 * is true for keys a scheduled job owns (CONSENT_RENEWAL), which the
 * compose picker hides because their merge fields only resolve inside
 * that job. {@code copyRoles} and {@code copyMode} carry the template's
 * internal-copy policy.
 * <p>
 * The three routing fields answer "what is this letter FOR": the explicit
 * {@code triggerKey} assignment, the {@code effectiveTrigger} the mailer
 * would actually resolve it on (the assignment, else the letter's own key
 * when that key is a reserved trigger), and {@code connected} — false
 * meaning nothing in the pipeline will ever reach this letter.
 */
public record EmailTemplateResponse(
        String uuid,
        String templateKey,
        /** The explicit assignment, or null when this letter has claimed no moment. */
        String triggerKey,
        /** triggerKey, else templateKey when that is a trigger, else null. */
        String effectiveTrigger,
        /** {@code effectiveTrigger != null} — false means nothing fires this letter. */
        boolean connected,
        String name,
        String subject,
        String body,
        String bodyFormat,
        boolean autoSend,
        boolean active,
        boolean trigger,
        boolean systemOwned,
        List<String> copyRoles,
        String copyMode,
        LocalDateTime updatedAt
) {
    public static EmailTemplateResponse of(RecruitmentEmailTemplate template) {
        String effectiveTrigger = RecruitmentEmailService.effectiveTrigger(template);
        return new EmailTemplateResponse(
                template.getUuid(),
                template.getTemplateKey(),
                template.getTriggerKey(),
                effectiveTrigger,
                effectiveTrigger != null,
                template.getName(),
                template.getSubject(),
                template.getBody(),
                template.getBodyFormat().name(),
                template.isAutoSend(),
                template.isActive(),
                RecruitmentEmailService.isTriggerKey(template.getTemplateKey()),
                RecruitmentEmailService.isSystemKey(template.getTemplateKey()),
                RecruitmentEmailCopyRole.parseCsv(template.getCopyRoles())
                        .stream().map(Enum::name).toList(),
                template.getCopyMode().name(),
                template.getUpdatedAt());
    }
}

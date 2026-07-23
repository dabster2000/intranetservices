package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;

import java.time.LocalDateTime;

/**
 * One candidate-email template (P15). {@code trigger} is true when the
 * key is a reactor-trigger key (ACKNOWLEDGEMENT, REJECTION_*, STAGE_*) —
 * the frontend renders the trigger explainer from it.
 */
public record EmailTemplateResponse(
        String uuid,
        String templateKey,
        String name,
        String subject,
        String body,
        boolean autoSend,
        boolean active,
        boolean trigger,
        LocalDateTime updatedAt
) {
    public static EmailTemplateResponse of(RecruitmentEmailTemplate template) {
        return new EmailTemplateResponse(
                template.getUuid(),
                template.getTemplateKey(),
                template.getName(),
                template.getSubject(),
                template.getBody(),
                template.isAutoSend(),
                template.isActive(),
                RecruitmentEmailService.isTriggerKey(template.getTemplateKey()),
                template.getUpdatedAt());
    }
}

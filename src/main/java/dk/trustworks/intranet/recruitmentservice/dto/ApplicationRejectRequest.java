package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /recruitment/applications/{uuid}/reject}.
 * The coded reason is mandatory (reporting aggregates on it — spec §4.2
 * invariant 4); the optional free-text note lands in the
 * {@code APPLICATION_REJECTED} event's {@code pii} block, never in a state
 * table.
 * <p>
 * The two email fields (2026-09-02) let the rejecter overrule the letter
 * the reason/stage chain would pick. They are the answer to the workflow
 * the ATS actually had: the reject dialog told you which letter was coming
 * and gave you no way to change it, so a recruiter who disagreed rejected
 * in silence and then composed a separate email by hand. Both are recorded
 * on the event, so what the candidate received stays reconstructable from
 * the stream.
 *
 * @param reasonCode             the coded reason; mandatory
 * @param note                   colleague-facing elaboration → event pii
 * @param emailTemplateKey       a specific template to send instead of the
 *                               one {@code rejectionKeyChain} would choose;
 *                               null means "let the rule decide"
 * @param suppressCandidateEmail {@code true} sends the candidate nothing at
 *                               all — for the case already handled outside
 *                               the system (a phone call, a letter already
 *                               written). Mutually exclusive with
 *                               {@code emailTemplateKey}.
 */
public record ApplicationRejectRequest(
        @NotNull(message = "reasonCode is required — pick the closest coded reason; elaborate in the note")
        RecruitmentRejectionReason reasonCode,

        @Size(max = 2000, message = "note must be at most 2000 characters")
        String note,

        @Size(max = 60, message = "emailTemplateKey must be at most 60 characters")
        String emailTemplateKey,

        Boolean suppressCandidateEmail
) {

    /** The pre-2026-09-02 shape: let the rule pick the letter. */
    public ApplicationRejectRequest(RecruitmentRejectionReason reasonCode, String note) {
        this(reasonCode, note, null, null);
    }

    /** True only for an explicit opt-out; a null body field means "as usual". */
    public boolean suppressesCandidateEmail() {
        return Boolean.TRUE.equals(suppressCandidateEmail);
    }

    /** The override key, trimmed, or null when the rule should decide. */
    public String normalizedTemplateKey() {
        return emailTemplateKey == null || emailTemplateKey.isBlank()
                ? null : emailTemplateKey.trim();
    }
}

package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.EmployeeDocumentAudit;

/**
 * One lifecycle event in a document's history — the read side of the
 * GDPR art. 30 trail, shaped for display (spec §6.2).
 *
 * <p>Only lifecycle actions reach this DTO (filed / migrated / changed /
 * archived). Access events (DOWNLOAD) are deliberately excluded by the
 * resource query: the employee-facing history answers "where did this
 * come from and has it changed?", not "which colleague opened my file".</p>
 *
 * <p>{@code actorRole} is the display-safe attribution — SELF when the
 * subject acted on their own file, HR for anyone else, SYSTEM for batch
 * writers (migration, promotion, onboarding). {@code actorName} is
 * resolved for the HR view; the self-view BFF strips it before the
 * response reaches the browser.</p>
 */
public record EmployeeDocumentHistoryEntryDTO(
        String action,
        String actorRole,
        String actorName,
        String detail,
        String createdAt) {

    public static final String ACTOR_SELF = "SELF";
    public static final String ACTOR_HR = "HR";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    public static EmployeeDocumentHistoryEntryDTO from(EmployeeDocumentAudit audit,
                                                       String subjectUserUuid,
                                                       String actorName) {
        return new EmployeeDocumentHistoryEntryDTO(
                audit.getAction().name(),
                actorRole(audit.getActorUuid(), subjectUserUuid),
                actorName,
                audit.getDetail(),
                audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString());
    }

    private static String actorRole(String actorUuid, String subjectUserUuid) {
        if (actorUuid == null) return ACTOR_SYSTEM;
        return actorUuid.equals(subjectUserUuid) ? ACTOR_SELF : ACTOR_HR;
    }
}

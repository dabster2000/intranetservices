package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Security classification for a document template.
 *
 * <p>Recruitment dossier templates contain offer/contract structure and may
 * only be exposed through the recruitment dossier authorization boundary (or
 * to HR/ADMIN template managers). Employee-signing templates remain available
 * to the existing employee signing flow.</p>
 */
public enum TemplateUsage {
    EMPLOYEE_SIGNING,
    RECRUITMENT_DOSSIER
}

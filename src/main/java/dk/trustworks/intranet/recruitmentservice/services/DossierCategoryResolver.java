package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Resolves a dossier's employee-document category from the template it was
 * generated with.
 *
 * <p>Its own bean purely so {@link S3EmployeePromotionService} can enumerate
 * what to promote without touching Panache: {@code findById} is inherited
 * from {@code PanacheEntityBase}, so {@code mockStatic(CandidateDossier.class)}
 * intercepts nothing (Mockito resolves static mocks on the <em>declaring</em>
 * class) and the selection rule could not otherwise be tested on the DB-free
 * tier — which is the deploy gate.</p>
 */
@ApplicationScoped
public class DossierCategoryResolver {

    /**
     * Dossier template category → employee-document category.
     *
     * @return {@code OTHER} when there is no dossier to resolve at all,
     *         {@code CONTRACT} when the dossier exists but its template does
     *         not (a dossier is contract paperwork until proven otherwise).
     */
    public EmployeeDocumentCategory resolve(String dossierUuid) {
        if (dossierUuid == null) return EmployeeDocumentCategory.OTHER;
        CandidateDossier dossier = CandidateDossier.findById(dossierUuid);
        if (dossier == null || dossier.getTemplateUuid() == null) return EmployeeDocumentCategory.CONTRACT;
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(dossier.getTemplateUuid());
        if (template == null) return EmployeeDocumentCategory.CONTRACT;
        return EmployeeDocumentCategory.fromTemplateCategory(template.getCategory());
    }
}

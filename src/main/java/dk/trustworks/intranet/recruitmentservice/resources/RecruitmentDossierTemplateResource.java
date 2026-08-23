package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.services.TemplateService;
import dk.trustworks.intranet.recruitmentservice.dto.DossierResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.DossierService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * Candidate-context read for the template behind an offer dossier.
 *
 * <p>The generic {@code /templates/{uuid}} endpoint cannot decide whether a
 * TEAMLEAD is the eligible named hiring owner, so recruitment templates are
 * HR/ADMIN-only there. This endpoint supplies the missing candidate context
 * and reuses {@link RecruitmentVisibility#canReadDossier(String, RecruitmentCandidate)},
 * the same authoritative predicate as the dossier itself.</p>
 */
@Path("/recruitment/candidates/{candidateUuid}/dossier/template")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentDossierTemplateResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    DossierService dossierService;

    @Inject
    TemplateService templateService;

    @GET
    public DocumentTemplateDTO get(@PathParam("candidateUuid") UUID candidateUuid) {
        enforceFlag();
        String viewer = requestHeaderHolder.getUserUuid();
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || viewer == null || viewer.isBlank()
                || !visibility.canReadDossier(viewer, candidate)) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }

        DossierResponse dossier = dossierService.loadForCandidate(candidateUuid)
                .orElseThrow(() -> new NotFoundException(
                        "Dossier not found for candidate: " + candidateUuid));

        // A persisted candidate_dossiers reference is itself authoritative
        // recruitment classification, even if a stale row says otherwise.
        return templateService.findByUuid(dossier.templateUuid());
    }

    private void enforceFlag() {
        if (!featureFlag.isEnabled() && !scopeContext.hasScope(ADMIN_WILDCARD)) {
            throw new NotFoundException("Resource not found");
        }
    }
}

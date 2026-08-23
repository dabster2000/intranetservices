package dk.trustworks.intranet.documentservice.security;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateDocumentEntity;
import dk.trustworks.intranet.documentservice.model.enums.TemplateUsage;
import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * End-user authorization for generic document-template endpoints.
 *
 * <p>The service JWT only proves that the BFF may call the backend. Human
 * authorization comes from {@code X-Requested-By}; missing or malformed
 * values therefore fail closed. Generic recruitment-template reads have no
 * candidate context and are HR/ADMIN-only. Candidate-context dossier reads
 * use the recruitment module's canonical dossier predicate instead.</p>
 */
@ApplicationScoped
public class TemplateAccessPolicy {

    private static final Set<String> TEMPLATE_MANAGERS = Set.of("ADMIN", "HR");

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** Resolve and validate the acting human, never the system JWT principal. */
    public String requireActor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new ForbiddenException("Template access requires an identified user");
        }
        try {
            UUID.fromString(actor);
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("Template access requires a valid user");
        }
        return actor;
    }

    public boolean isManager() {
        String actor = requireActor();
        return rolesOf(actor).stream().anyMatch(TEMPLATE_MANAGERS::contains);
    }

    public void requireManager() {
        if (!isManager()) {
            throw new ForbiddenException("Template management is restricted to HR and ADMIN");
        }
    }

    /**
     * An unclassified collection spans both usages and is manager-only.
     * Employee-signing collections may be read by an identified employee;
     * recruitment collections remain manager-only because no candidate is
     * present to authorize a named hiring owner.
     */
    public void requireCollectionRead(TemplateUsage requestedUsage) {
        requireActor();
        if (requestedUsage != TemplateUsage.EMPLOYEE_SIGNING) {
            requireManager();
        }
    }

    public List<DocumentTemplateDTO> filterCollection(
            List<DocumentTemplateDTO> templates,
            TemplateUsage requestedUsage) {
        requireActor();
        if (requestedUsage == null) {
            return templates;
        }
        if (requestedUsage == TemplateUsage.RECRUITMENT_DOSSIER) {
            return templates.stream().filter(this::isRecruitmentTemplate).toList();
        }
        return templates.stream().filter(template -> !isRecruitmentTemplate(template)).toList();
    }

    /** Generic single-template metadata reads fail closed for recruitment. */
    public void requireTemplateRead(DocumentTemplateDTO template) {
        if (!isManager() && isRecruitmentTemplate(template)) {
            throw new ForbiddenException("Recruitment dossier templates require dossier access");
        }
    }

    /**
     * Raw document reads inspect every owning template. An unlinked file, a
     * stale classification on a dossier-linked template, or any recruitment
     * owner is manager-only.
     */
    public void requireDocumentRead(String fileUuid) {
        if (isManager()) {
            return;
        }
        List<TemplateDocumentEntity> owners = TemplateDocumentEntity.findByFileUuid(fileUuid);
        if (owners.isEmpty() || owners.stream().anyMatch(document ->
                isRecruitmentTemplate(document.getTemplate()))) {
            throw new ForbiddenException("Recruitment template documents require dossier access");
        }
    }

    public boolean isRecruitmentTemplate(DocumentTemplateDTO template) {
        return template == null
                || template.getTemplateUsage() != TemplateUsage.EMPLOYEE_SIGNING
                || isDossierLinked(template.getUuid());
    }

    public boolean isRecruitmentTemplate(DocumentTemplateEntity template) {
        return template == null
                || template.getTemplateUsage() != TemplateUsage.EMPLOYEE_SIGNING
                || isDossierLinked(template.getUuid());
    }

    private boolean isDossierLinked(String templateUuid) {
        return templateUuid == null || templateUuid.isBlank()
                || CandidateDossier.count("templateUuid", templateUuid) > 0;
    }

    private Set<String> rolesOf(String userUuid) {
        return Role.<Role>list("useruuid", userUuid).stream()
                .map(Role::getRole)
                .map(role -> role == null ? "" : role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}

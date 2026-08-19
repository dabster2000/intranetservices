package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateDefaultSignerEntity;
import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ONE place a dossier's {@code template_uuid} is validated and its
 * initial signer configuration is derived. Both dossier-creating paths call
 * it: {@link CandidateService#createCandidate} (the candidate-creation
 * branch) and {@link DossierService#createForCandidate} (the manual
 * "create offer dossier" action on an existing candidate).
 *
 * <h3>Why the template check has to exist at all</h3>
 * {@link #seedSignersFromTemplate(String)} queries only the <em>child</em>
 * {@code template_default_signers} table, so a nonexistent template is
 * indistinguishable from a real one with no default signers — both yield
 * {@code "[]"}. Bean validation is inert in this backend (no
 * {@code quarkus-hibernate-validator}), so {@code @Size} does not save it
 * either. A bad {@code templateUuid} therefore used to produce a dossier
 * that looked fine, seeded an empty signer list, rendered blank
 * placeholders into a real employment contract and — via
 * {@link DossierCategoryResolver}'s {@code CONTRACT} fallback — filed the
 * signed PDF under the wrong {@code EmployeeDocumentCategory} at hire, with
 * no API path to undo any of it.
 *
 * <h3>Why it is its own bean</h3>
 * Same reason as {@link DossierCategoryResolver}: {@code findById} is
 * inherited from {@code PanacheEntityBase}, so {@code mockStatic} cannot
 * intercept it (Mockito resolves static mocks on the <em>declaring</em>
 * class). Keeping the Panache reads here — and the decision in the pure
 * {@link #requireUsable(String, DocumentTemplateEntity)} — is what lets the
 * DB-free tier, which is the deploy gate, exercise every guard branch.
 */
@ApplicationScoped
public class DossierTemplateResolver {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Guards 3 and 4 of the create-dossier contract: the template reference
     * must be present, must resolve, and must still be active.
     *
     * @param templateUuid the caller-supplied {@code document_templates.uuid}
     * @return the resolved, active template — never null
     * @throws WebApplicationException 400 with a {@code {error, message}} body
     *         carrying {@code TEMPLATE_REQUIRED}, {@code TEMPLATE_NOT_FOUND}
     *         or {@code TEMPLATE_INACTIVE}
     */
    public DocumentTemplateEntity requireActiveTemplate(String templateUuid) {
        String trimmed = templateUuid == null ? null : templateUuid.trim();
        DocumentTemplateEntity template = trimmed == null || trimmed.isEmpty()
                ? null
                : DocumentTemplateEntity.findById(trimmed);
        return requireUsable(trimmed, template);
    }

    /**
     * The decision half of {@link #requireActiveTemplate(String)}, split out
     * so every branch is reachable without a database.
     *
     * @param templateUuid the (already trimmed) reference the caller supplied
     * @param template     what it resolved to, or {@code null}
     */
    static DocumentTemplateEntity requireUsable(String templateUuid, DocumentTemplateEntity template) {
        if (templateUuid == null || templateUuid.isBlank()) {
            throw templateError("TEMPLATE_REQUIRED",
                    "templateUuid is required — pick the contract template the dossier is built from.");
        }
        if (template == null) {
            throw templateError("TEMPLATE_NOT_FOUND",
                    "No document template with uuid " + templateUuid + ".");
        }
        if (!template.isActive()) {
            throw templateError("TEMPLATE_INACTIVE",
                    "Template " + template.getName() + " is no longer active — pick a current one.");
        }
        return template;
    }

    /**
     * Seed the dossier's signers_config from the template's default signers,
     * sorted by signer group then display order. ${PLACEHOLDER} tokens in
     * name/email are preserved as-is; revision snapshots resolve them at
     * send time. Returns "[]" when the template defines no defaults so the
     * not-null DB column always carries a valid JSON array.
     */
    public String seedSignersFromTemplate(String templateUuid) {
        List<TemplateDefaultSignerEntity> defaults = TemplateDefaultSignerEntity
                .find("template.uuid = ?1 ORDER BY signerGroup, displayOrder", templateUuid)
                .list();

        List<SignerConfigDto> signers = new ArrayList<>(defaults.size());
        for (TemplateDefaultSignerEntity s : defaults) {
            signers.add(new SignerConfigDto(
                    String.valueOf(s.getSignerGroup()),
                    s.getName(),
                    s.getEmail(),
                    s.isSigning(),
                    s.isNeedsCpr(),
                    s.getRole(),
                    null
            ));
        }

        try {
            return objectMapper.writeValueAsString(signers);
        } catch (JsonProcessingException e) {
            throw CandidateService.jsonError("write", e);
        }
    }

    /**
     * 400 with the contract's {@code {error, message}} body — the dialog on
     * the Offer &amp; Contract tab renders both fields verbatim, so the
     * message is written for the recruiter, not for a log.
     */
    private static WebApplicationException templateError(String code, String message) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", code, "message", message))
                .build());
    }
}

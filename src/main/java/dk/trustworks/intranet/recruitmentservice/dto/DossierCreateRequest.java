package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /recruitment/candidates/{uuid}/dossier} — the manual
 * "create offer dossier" step on the profile's Offer &amp; Contract tab.
 * <p>
 * Only the template is supplied, and it is required: the prod template set
 * spans three legal entities, several role tiers and non-offer documents
 * (Fratrædelseserklæring, Lønregulering), while {@code document_templates}
 * has no company column and {@code recruitment_positions} has no template
 * column — so no server-side rule can pick correctly. A human chooses.
 * <p>
 * Bean validation is inert in this backend (house rule) — the annotation is
 * documentation; {@code DossierTemplateResolver} enforces it explicitly and
 * answers 400 {@code TEMPLATE_REQUIRED}.
 */
public record DossierCreateRequest(
        @NotBlank String templateUuid) {
}

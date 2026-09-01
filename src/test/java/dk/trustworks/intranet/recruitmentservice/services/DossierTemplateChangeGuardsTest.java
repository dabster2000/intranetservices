package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.enums.TemplateUsage;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The change-template guard chain (the misclick escape hatch) and the
 * shape of the event it emits, as pure functions of the facts they depend
 * on — the {@link DossierCreateGuardsTest} pattern, DB-free because the
 * fast tier is the deploy gate.
 *
 * <p>The two 409s are the policy: a dossier with revision history keeps
 * its template forever (the honest correction is branching), and a swap
 * onto a template another dossier of the candidate already sits on is
 * refused rather than left to become a duplicate-key 500 on
 * {@code uk_dossier_candidate_template}.</p>
 */
class DossierTemplateChangeGuardsTest {

    private static final String CANDIDATE = "d2bd33b3-4fe9-4ca3-915a-91a004e70d77";
    private static final String TEMPLATE = "0b4a5cd1-2a2c-4d0f-9a54-0f5c2f1a7c11";
    private static final String NEW_TEMPLATE = "6e0d9f43-79c9-4a7f-8b0a-3d0b2a5cf102";
    private static final String DOSSIER = "03f122c2-0f3a-4e7c-826a-9a424d3e5cdc";
    private static final String POSITION = "9c1e77a2-4c65-4a3f-8f2c-c0b6b6ae5f30";
    private static final UUID ACTOR = UUID.fromString("5f2c9c11-77a4-4f3a-9c2b-88a8f0d0b111");

    // ---- The template reference (shared with the create chain) ---------------

    @Test
    void aBlankTemplateUuid_is400_TEMPLATE_REQUIRED() {
        assertEquals("TEMPLATE_REQUIRED", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange("   ", null, 0, false))));
    }

    @Test
    void aTemplateThatDoesNotResolve_is400_TEMPLATE_NOT_FOUND() {
        assertEquals("TEMPLATE_NOT_FOUND", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange(NEW_TEMPLATE, null, 0, false))));
    }

    @Test
    void aRetiredTemplate_is400_TEMPLATE_INACTIVE() {
        assertEquals("TEMPLATE_INACTIVE", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange(
                        NEW_TEMPLATE, template(NEW_TEMPLATE, false), 0, false))));
    }

    @Test
    void anEmployeeSigningTemplate_is400_TEMPLATE_NOT_RECRUITMENT() {
        DocumentTemplateEntity employeeTemplate = template(NEW_TEMPLATE, true);
        employeeTemplate.setTemplateUsage(TemplateUsage.EMPLOYEE_SIGNING);

        assertEquals("TEMPLATE_NOT_RECRUITMENT", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange(
                        NEW_TEMPLATE, employeeTemplate, 0, false))));
    }

    // ---- The revision lock ----------------------------------------------------

    @Test
    void aDossierWithRevisions_is409_DOSSIER_HAS_REVISIONS() {
        // The first send snapshots the template into a revision — after
        // that, swapping would make the draft disagree with its own
        // history. The honest correction is branching.
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange(
                        NEW_TEMPLATE, template(NEW_TEMPLATE, true), 1, false));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        assertEquals("DOSSIER_HAS_REVISIONS", errorCodeOf(e));
    }

    @Test
    void theTemplateIsCheckedBeforeTheRevisionLock() {
        // Same contract as the create chain: the recruiter is told about the
        // thing they can fix from the dialog first.
        assertEquals("TEMPLATE_NOT_FOUND", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange(NEW_TEMPLATE, null, 3, true))));
    }

    // ---- The unique-pair collision --------------------------------------------

    @Test
    void aTargetAnotherDossierSitsOn_is409_DOSSIER_EXISTS() {
        // Without this guard the swap dies as a duplicate-key 500 on
        // uk_dossier_candidate_template when a CLOSED dossier already
        // holds the target template.
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveTemplateChange(
                        NEW_TEMPLATE, template(NEW_TEMPLATE, true), 0, true));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        assertEquals("DOSSIER_EXISTS", errorCodeOf(e));
    }

    @Test
    void aCleanSwap_returnsTheResolvedTemplate() {
        DocumentTemplateEntity target = template(NEW_TEMPLATE, true);
        assertSame(target, DossierService.resolveTemplateChange(NEW_TEMPLATE, target, 0, false));
    }

    // ---- The event ------------------------------------------------------------

    @Test
    void theEvent_carriesOldAndNewTemplate_structuralOnly() {
        RecruitmentEventBuilder event = DossierService.dossierTemplateChangedEvent(
                activeCandidate(), dossier(NEW_TEMPLATE), TEMPLATE, null, null, ACTOR);

        Map<String, Object> payload = map(event, "payload");
        assertEquals(DOSSIER, payload.get("dossier_uuid"));
        assertEquals(TEMPLATE, payload.get("old_template_uuid"));
        assertEquals(NEW_TEMPLATE, payload.get("template_uuid"));
        assertEquals(ACTOR.toString(), field(event, "actorUuid"));
    }

    @Test
    void aPartnerTrackPosition_pairsCircleVisibilityWithThePosition() {
        // Both stamps are load-bearing for CIRCLE readers — the secrecy of a
        // partner search must not depend on which dossier command ran last.
        RecruitmentPosition partner = new RecruitmentPosition();
        partner.setUuid(POSITION);
        partner.setHiringTrack(RecruitmentHiringTrack.PARTNER);

        RecruitmentEventBuilder event = DossierService.dossierTemplateChangedEvent(
                activeCandidate(), dossier(NEW_TEMPLATE), TEMPLATE, null, partner, ACTOR);

        assertEquals(POSITION, field(event, "positionUuid"));
        assertEquals("CIRCLE", String.valueOf(field(event, "visibility")));
    }

    // ---- Builders --------------------------------------------------------------

    private static DocumentTemplateEntity template(String uuid, boolean active) {
        DocumentTemplateEntity template = new DocumentTemplateEntity();
        template.setUuid(uuid);
        template.setName("Ansættelseskontrakt");
        template.setActive(active);
        return template;
    }

    private static RecruitmentCandidate activeCandidate() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(CANDIDATE);
        candidate.setStatus(CandidateStatus.ACTIVE);
        return candidate;
    }

    private static CandidateDossier dossier(String templateUuid) {
        CandidateDossier dossier = new CandidateDossier();
        dossier.setUuid(DOSSIER);
        dossier.setCandidateUuid(CANDIDATE);
        dossier.setTemplateUuid(templateUuid);
        return dossier;
    }

    // ---- Reading the refusal and the builder (DossierCreateGuardsTest idiom) --

    private static String errorCodeOf(WebApplicationException e) {
        Object entity = e.getResponse().getEntity();
        assertTrue(entity instanceof Map,
                "the dialog renders {error, message} verbatim; actual entity: " + entity);
        return String.valueOf(((Map<?, ?>) entity).get("error"));
    }

    private static Object field(RecruitmentEventBuilder builder, String name) {
        try {
            Field f = RecruitmentEventBuilder.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(builder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(RecruitmentEventBuilder builder, String name) {
        return (Map<String, Object>) field(builder, name);
    }
}

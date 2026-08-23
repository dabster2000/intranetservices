package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.enums.TemplateUsage;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The create-offer-dossier guard chain and the shape of the event it emits,
 * as pure functions of the facts they depend on.
 *
 * <p>No {@code @QuarkusTest}: this is the DB-free tier, which is the deploy
 * gate. That is why {@code DossierService.resolveReopenTarget} takes the
 * already-loaded template, candidate and dossier list rather than reading
 * them itself — {@code findById} is inherited from {@code PanacheEntityBase},
 * so {@code mockStatic} intercepts nothing (Mockito resolves static mocks on
 * the <em>declaring</em> class) and the branches could not otherwise be
 * reached without a database.</p>
 *
 * <p>Two of these branches exist to stop a 500 rather than to state a policy,
 * and both are pinned here on purpose. Reopening (rather than re-inserting)
 * on the same template is what keeps {@code uk_dossier_candidate_template
 * UNIQUE (candidate_uuid, template_uuid)} from turning a legitimate retry
 * into a duplicate-key error; refusing a second dossier on a <em>different</em>
 * template is what keeps {@code S3EmployeePromotionService} — which groups
 * revisions by dossier — from multiplying what lands in the employee record
 * at hire.</p>
 */
class DossierCreateGuardsTest {

    private static final String CANDIDATE = "d2bd33b3-4fe9-4ca3-915a-91a004e70d77";
    private static final String TEMPLATE = "0b4a5cd1-2a2c-4d0f-9a54-0f5c2f1a7c11";
    private static final String OTHER_TEMPLATE = "6e0d9f43-79c9-4a7f-8b0a-3d0b2a5cf102";
    private static final String DOSSIER = "03f122c2-0f3a-4e7c-826a-9a424d3e5cdc";
    private static final String APPLICATION = "cd35b065-1ade-4985-bd3d-28a0d33af0bc";
    private static final String POSITION = "9c1e77a2-4c65-4a3f-8f2c-c0b6b6ae5f30";
    private static final UUID ACTOR = UUID.fromString("5f2c9c11-77a4-4f3a-9c2b-88a8f0d0b111");

    // ---- Guard 3 + 4: the template reference ---------------------------------

    @Test
    void aBlankTemplateUuid_is400_TEMPLATE_REQUIRED() {
        // Bean validation is inert in this backend, so the only thing between
        // an empty picker and a dossier with no template is this check.
        assertEquals("TEMPLATE_REQUIRED", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        "   ", null, activeCandidate(), List.of()))));
    }

    @Test
    void aTemplateThatDoesNotResolve_is400_TEMPLATE_NOT_FOUND() {
        // The seeding query reads only the CHILD template_default_signers
        // table, so a nonexistent template used to be indistinguishable from
        // a real one with no default signers — both seed "[]".
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, null, activeCandidate(), List.of()));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus());
        assertEquals("TEMPLATE_NOT_FOUND", errorCodeOf(e));
    }

    @Test
    void aRetiredTemplate_is400_TEMPLATE_INACTIVE() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, template(TEMPLATE, false), activeCandidate(), List.of()));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus());
        assertEquals("TEMPLATE_INACTIVE", errorCodeOf(e));
    }

    @Test
    void anEmployeeSigningTemplate_is400_TEMPLATE_NOT_RECRUITMENT() {
        DocumentTemplateEntity employeeTemplate = template(TEMPLATE, true);
        employeeTemplate.setTemplateUsage(TemplateUsage.EMPLOYEE_SIGNING);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, employeeTemplate, activeCandidate(), List.of()));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus());
        assertEquals("TEMPLATE_NOT_RECRUITMENT", errorCodeOf(e));
    }

    @Test
    void theTemplateIsCheckedBeforeTheCandidateStatus() {
        // The contract pins the order: a recruiter with both problems must be
        // told about the template they can fix from the dialog, not about a
        // candidate status they cannot.
        RecruitmentCandidate declined = candidate(CandidateStatus.DECLINED);

        assertEquals("TEMPLATE_NOT_FOUND", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(TEMPLATE, null, declined, List.of()))));
    }

    // ---- Guard 5: the candidate must be ACTIVE --------------------------------

    @ParameterizedTest
    @EnumSource(value = CandidateStatus.class,
            names = {"POOLED", "HIRED", "DECLINED", "WITHDRAWN", "ANONYMIZED"})
    void aNonActiveCandidate_is409_CANDIDATE_NOT_ACTIVE(CandidateStatus status) {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, template(TEMPLATE, true), candidate(status), List.of()));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        assertEquals("CANDIDATE_NOT_ACTIVE", errorCodeOf(e));
    }

    // ---- Guard 6: an OPEN dossier already exists ------------------------------

    @Test
    void anOpenDossierOnTheSameTemplate_is409_DOSSIER_EXISTS() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, template(TEMPLATE, true), activeCandidate(),
                        List.of(dossier(TEMPLATE, DossierStatus.OPEN))));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        assertEquals("DOSSIER_EXISTS", errorCodeOf(e));
    }

    @Test
    void anOpenDossierOnAnotherTemplate_alsoRefuses_beforeTheReopenRuleIsConsulted() {
        // Rule 6 scans every dossier before rules 7/8 look at any one of them:
        // an OPEN dossier is an answer on its own, whichever template it is
        // on. Ordering the loops the other way would silently reopen a CLOSED
        // dossier while an OPEN one was live — two dossiers, which is exactly
        // what rule 8 exists to prevent.
        List<CandidateDossier> existing = List.of(
                dossier(TEMPLATE, DossierStatus.CLOSED),
                dossier("b3a8f4d0-6f1a-4c2e-9a70-4f9a1cf2e001", OTHER_TEMPLATE, DossierStatus.OPEN));

        assertEquals("DOSSIER_EXISTS", errorCodeOf(assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, template(TEMPLATE, true), activeCandidate(), existing))));
    }

    // ---- Guard 7: a CLOSED dossier on the same template reopens ---------------

    @Test
    void aClosedDossierOnTheSameTemplate_isReopenedRatherThanReinserted() {
        CandidateDossier closed = dossier(TEMPLATE, DossierStatus.CLOSED);

        CandidateDossier target = DossierService.resolveReopenTarget(
                TEMPLATE, template(TEMPLATE, true), activeCandidate(), List.of(closed));

        assertSame(closed, target,
                "re-inserting would hit uk_dossier_candidate_template and answer 500");
    }

    @Test
    void reopenRestoresTheDraftWithoutTouchingIt() {
        // Placeholder values, signers and appendices from before the candidate
        // went terminal are exactly what the recruiter wants back.
        CandidateDossier closed = dossier(TEMPLATE, DossierStatus.CLOSED);
        closed.setPlaceholderValuesJson("{\"START_DATE\":\"2026-09-01\"}");
        closed.setSignersConfigJson("[{\"group\":\"1\"}]");

        closed.reopen();

        assertEquals(DossierStatus.OPEN, closed.getStatus());
        assertEquals("{\"START_DATE\":\"2026-09-01\"}", closed.getPlaceholderValuesJson());
        assertEquals("[{\"group\":\"1\"}]", closed.getSignersConfigJson());
    }

    // ---- Guard 8: a CLOSED dossier on a different template refuses ------------

    @Test
    void aClosedDossierOnAnotherTemplate_is409_namingTheTemplateItIsOn() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> DossierService.resolveReopenTarget(
                        TEMPLATE, template(TEMPLATE, true), activeCandidate(),
                        List.of(dossier(OTHER_TEMPLATE, DossierStatus.CLOSED))));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        assertEquals("DOSSIER_EXISTS", errorCodeOf(e));
        assertTrue(messageOf(e).contains(OTHER_TEMPLATE),
                "the recruiter cannot act on 'a dossier exists' without knowing which one; "
                        + "actual message: " + messageOf(e));
    }

    // ---- Happy path ----------------------------------------------------------

    @Test
    void anActiveCandidateWithNoDossier_insertsRatherThanReopens() {
        assertNull(DossierService.resolveReopenTarget(
                TEMPLATE, template(TEMPLATE, true), activeCandidate(), List.of()));
    }

    @Test
    void theMatchingClosedDossierIsReopened_notWhicheverCameFirst() {
        // Prod has never had a candidate with two dossiers and rule 8 keeps it
        // that way, but the reopen must pick by template rather than by list
        // position — a defensive ordering assumption is how the wrong contract
        // would come back to life.
        CandidateDossier other = dossier(
                "b3a8f4d0-6f1a-4c2e-9a70-4f9a1cf2e001", OTHER_TEMPLATE, DossierStatus.CLOSED);
        CandidateDossier matching = dossier(TEMPLATE, DossierStatus.CLOSED);

        assertSame(matching, DossierService.resolveReopenTarget(
                TEMPLATE, template(TEMPLATE, true), activeCandidate(),
                List.of(other, matching)));
    }

    // ---- The event -----------------------------------------------------------

    @Test
    void theEventCarriesTheStructuralFactsAndNoPii() {
        RecruitmentEventBuilder event = DossierService.dossierCreatedEvent(
                activeCandidate(), dossier(TEMPLATE, DossierStatus.OPEN), false,
                null, null, ACTOR);

        assertEquals(RecruitmentEventType.DOSSIER_CREATED, field(event, "type"));
        assertEquals(CANDIDATE, field(event, "candidateUuid"));
        assertEquals(ACTOR.toString(), field(event, "actorUuid"));

        Map<String, Object> payload = map(event, "payload");
        assertEquals(TEMPLATE, payload.get("template_uuid"));
        assertEquals(DOSSIER, payload.get("dossier_uuid"));
        assertEquals(false, payload.get("reopened"));
        assertTrue(map(event, "pii").isEmpty(),
                "this command has no personal data to record — a pii section here "
                        + "would only be a leak from the payload side");
    }

    @Test
    void aReopenSaysSo() {
        RecruitmentEventBuilder event = DossierService.dossierCreatedEvent(
                activeCandidate(), dossier(TEMPLATE, DossierStatus.OPEN), true,
                null, null, ACTOR);

        assertEquals(true, map(event, "payload").get("reopened"));
    }

    @Test
    void anOpenApplicationIsStampedOnTheEnvelopeAndThePayload() {
        RecruitmentEventBuilder event = DossierService.dossierCreatedEvent(
                activeCandidate(), dossier(TEMPLATE, DossierStatus.OPEN), false,
                application(RecruitmentStage.OFFER), practicePosition(), ACTOR);

        assertEquals(APPLICATION, field(event, "applicationUuid"));
        assertEquals(POSITION, field(event, "positionUuid"));
        assertEquals(APPLICATION, map(event, "payload").get("application_uuid"));
        assertEquals("OFFER", map(event, "payload").get("stage"));
    }

    @Test
    void aCandidateWithNoOpenApplication_stampsNeitherApplicationNorPosition() {
        // The pre-ATS dossier-only flow: no application, and no NORMAL/CIRCLE
        // decision to make either.
        RecruitmentEventBuilder event = DossierService.dossierCreatedEvent(
                activeCandidate(), dossier(TEMPLATE, DossierStatus.OPEN), false,
                null, null, ACTOR);

        assertNull(field(event, "applicationUuid"));
        assertNull(field(event, "positionUuid"));
        assertFalse(map(event, "payload").containsKey("application_uuid"));
        assertEquals(RecruitmentEventVisibility.NORMAL, field(event, "visibility"));
    }

    @Test
    void aPracticeTrackPosition_staysNORMAL() {
        RecruitmentEventBuilder event = DossierService.dossierCreatedEvent(
                activeCandidate(), dossier(TEMPLATE, DossierStatus.OPEN), false,
                application(RecruitmentStage.OFFER), practicePosition(), ACTOR);

        assertEquals(RecruitmentEventVisibility.NORMAL, field(event, "visibility"));
    }

    @Test
    void aPartnerTrackPosition_stampsBothCircleAndThePosition() {
        // BOTH stamps or neither: the readers of a CIRCLE event resolve the
        // circle FROM the event's position, so CIRCLE with a null position
        // fails closed for everyone — including the HR user who just created
        // the dossier.
        RecruitmentEventBuilder event = DossierService.dossierCreatedEvent(
                activeCandidate(), dossier(TEMPLATE, DossierStatus.OPEN), false,
                application(RecruitmentStage.OFFER), partnerPosition(), ACTOR);

        assertEquals(RecruitmentEventVisibility.CIRCLE, field(event, "visibility"));
        assertNotNull(field(event, "positionUuid"),
                "a CIRCLE event with no position is invisible to its own circle");
        assertEquals(POSITION, field(event, "positionUuid"));
    }

    // ---- Fixtures ------------------------------------------------------------

    private static RecruitmentCandidate activeCandidate() {
        return candidate(CandidateStatus.ACTIVE);
    }

    private static RecruitmentCandidate candidate(CandidateStatus status) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(CANDIDATE);
        candidate.setStatus(status);
        return candidate;
    }

    private static DocumentTemplateEntity template(String uuid, boolean active) {
        DocumentTemplateEntity template = new DocumentTemplateEntity();
        template.setUuid(uuid);
        template.setName("Ansættelseskontrakt");
        template.setActive(active);
        return template;
    }

    private static CandidateDossier dossier(String templateUuid, DossierStatus status) {
        return dossier(DOSSIER, templateUuid, status);
    }

    private static CandidateDossier dossier(String uuid, String templateUuid, DossierStatus status) {
        CandidateDossier dossier = new CandidateDossier();
        dossier.setUuid(uuid);
        dossier.setCandidateUuid(CANDIDATE);
        dossier.setTemplateUuid(templateUuid);
        dossier.setStatus(status);
        return dossier;
    }

    private static RecruitmentApplication application(RecruitmentStage stage) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid(APPLICATION);
        application.setCandidateUuid(CANDIDATE);
        application.setPositionUuid(POSITION);
        application.setStage(stage);
        return application;
    }

    private static RecruitmentPosition practicePosition() {
        return position(RecruitmentHiringTrack.PRACTICE_TEAM);
    }

    private static RecruitmentPosition partnerPosition() {
        return position(RecruitmentHiringTrack.PARTNER);
    }

    private static RecruitmentPosition position(RecruitmentHiringTrack track) {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setUuid(POSITION);
        position.setHiringTrack(track);
        return position;
    }

    // ---- Reading the refusal and the builder ---------------------------------

    private static String errorCodeOf(WebApplicationException e) {
        return String.valueOf(entityOf(e).get("error"));
    }

    private static String messageOf(WebApplicationException e) {
        return String.valueOf(entityOf(e).get("message"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entityOf(WebApplicationException e) {
        Object entity = e.getResponse().getEntity();
        assertTrue(entity instanceof Map,
                "the dialog renders {error, message} verbatim; actual entity: " + entity);
        return (Map<String, Object>) entity;
    }

    /**
     * Read a private field off the builder — its accessors are package-private
     * to {@code ...recruitmentservice.events}, out of reach here. Same idiom
     * as {@code RecruitmentS3StorageServiceTest}.
     */
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

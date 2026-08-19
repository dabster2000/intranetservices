package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.HardDeleteRequest;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCandidateHardDeleteService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The gates in front of {@code POST /recruitment/candidates/{uuid}/hard-delete}
 * (change C1) — the endpoint that irreversibly removes a person and every row
 * that references them.
 *
 * <p>The reason there is a per-user check at all, and therefore the reason
 * this class exists: {@code @RolesAllowed({"recruitment:admin"})} gates the
 * API <em>client</em>. The BFF's system token carries {@code admin:*} and
 * {@code AdminScopeAugmentor} expands that to every key in the catalogue, so
 * the annotation alone lets every employee's request reach the method body.
 * Only {@code visibility.canHardDeleteCandidate} stops a person.</p>
 *
 * <p>Database-free by construction: every case here is one that must be
 * refused <em>before</em> the candidate is loaded, so no Panache static is
 * ever reached. That ordering is itself the property under test — a gate
 * that runs after the lookup is a gate that has already leaked existence and
 * (for the reason check) already let a malformed body onto a destructive
 * path. The typed-name confirmation, which by definition needs the candidate,
 * is pinned through its static helper below.</p>
 */
class RecruitmentCandidateHardDeleteAuthzTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final String GOOD_REASON = "Created twice by mistake during the Airtable import";

    private RecruitmentResource resource;
    private RecruitmentVisibility visibility;
    private RecruitmentCandidateHardDeleteService hardDeleteService;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        visibility = mock(RecruitmentVisibility.class);
        hardDeleteService = mock(RecruitmentCandidateHardDeleteService.class);
        RecruitmentFeatureFlag featureFlag = mock(RecruitmentFeatureFlag.class);
        headers = new RequestHeaderHolder();
        headers.setUserUuid(ACTOR.toString());

        resource = new RecruitmentResource();
        resource.visibility = visibility;
        resource.hardDeleteService = hardDeleteService;
        resource.featureFlag = featureFlag;
        resource.requestHeaderHolder = headers;
        resource.scopeContext = mock(ScopeContext.class);

        when(featureFlag.isEnabled()).thenReturn(true);
    }

    // ---- Who may reach it at all --------------------------------------------

    @Test
    void aCallerWithoutRecruitmentAdmin_is403_andNothingIsDeleted() {
        when(visibility.canHardDeleteCandidate(ACTOR.toString())).thenReturn(false);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.hardDeleteCandidate(CANDIDATE,
                        new HardDeleteRequest("Jane Doe", GOOD_REASON)));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(hardDeleteService);
    }

    @Test
    void theAdminCheckRunsBeforeTheCandidateIsEvenLookedUp() {
        // If it ran after, a non-admin could use this endpoint as an
        // existence oracle for partner-track candidates — and the test would
        // blow up on the Panache static instead of returning 403.
        when(visibility.canHardDeleteCandidate(ACTOR.toString())).thenReturn(false);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.hardDeleteCandidate(UUID.randomUUID(),
                        new HardDeleteRequest("Anyone At All", GOOD_REASON)));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), thrown.getResponse().getStatus());
    }

    @Test
    void missingRequestedByHeader_is400_notAnUnattributableDelete() {
        headers.setUserUuid(null);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.hardDeleteCandidate(CANDIDATE,
                        new HardDeleteRequest("Jane Doe", GOOD_REASON)));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(hardDeleteService);
    }

    // ---- The reason is the only explanation that survives ---------------------

    @Test
    void aMissingBody_is400_beforeAnythingIsDeleted() {
        when(visibility.canHardDeleteCandidate(ACTOR.toString())).thenReturn(true);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.hardDeleteCandidate(CANDIDATE, null));

        assertEquals(400, thrown.getResponse().getStatus());
        verifyNoInteractions(hardDeleteService);
    }

    @Test
    void aTrivialReason_isRefused() {
        when(visibility.canHardDeleteCandidate(ACTOR.toString())).thenReturn(true);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.hardDeleteCandidate(CANDIDATE,
                        new HardDeleteRequest("Jane Doe", "oops")));

        assertEquals(400, thrown.getResponse().getStatus());
        assertTrue(String.valueOf(thrown.getResponse().getEntity()).contains("REASON_REQUIRED"));
        verifyNoInteractions(hardDeleteService);
    }

    @Test
    void aBlankReason_isRefused() {
        assertThrows(WebApplicationException.class, () -> RecruitmentResource
                .requireDeletionReason(new HardDeleteRequest("Jane Doe", "          ")));
    }

    @Test
    void aGoodReason_isReturnedTrimmed_andCappedToTheColumnWidth() {
        assertEquals(GOOD_REASON, RecruitmentResource.requireDeletionReason(
                new HardDeleteRequest("Jane Doe", "  " + GOOD_REASON + "  ")));

        String oversized = "x".repeat(1500);
        assertEquals(1000,
                RecruitmentResource.requireDeletionReason(
                        new HardDeleteRequest("Jane Doe", oversized)).length(),
                "the ledger column is VARCHAR(1000) — a longer reason must be truncated, "
                        + "not blow up the insert after the cascade has already run");
    }

    // ---- The typed confirmation (the RecruitmentGdprResource contract) --------

    @Test
    void aWrongTypedName_is400_CONFIRMATION_MISMATCH() {
        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> RecruitmentResource.requireTypedFullName("Jane", "Doe", "Jane Do"));

        assertEquals(400, thrown.getResponse().getStatus());
        assertEquals("{\"error\":\"CONFIRMATION_MISMATCH\"}", thrown.getResponse().getEntity(),
                "the body is copied verbatim from RecruitmentGdprResource.anonymize so the "
                        + "frontend can handle both dialogs with one branch");
    }

    @Test
    void theExactFullName_passes_andSurroundingWhitespaceIsForgiven() {
        assertDoesNotThrow(() -> RecruitmentResource.requireTypedFullName("Jane", "Doe", "Jane Doe"));
        assertDoesNotThrow(() -> RecruitmentResource.requireTypedFullName("Jane", "Doe", " Jane Doe "));
    }

    @Test
    void confirmationIsCaseSensitiveAndNotSatisfiedByAPartialName() {
        assertThrows(WebApplicationException.class,
                () -> RecruitmentResource.requireTypedFullName("Jane", "Doe", "jane doe"));
        assertThrows(WebApplicationException.class,
                () -> RecruitmentResource.requireTypedFullName("Jane", "Doe", "Jane"));
        assertThrows(WebApplicationException.class,
                () -> RecruitmentResource.requireTypedFullName("Jane", "Doe", null));
    }

    @Test
    void aNamelessCandidateCannotBeConfirmed_andThereforeCannotBeDeletedHere() {
        // Same deliberate behaviour as the anonymize path: with nothing to
        // type, there is no confirmation, so an empty string must not pass.
        assertThrows(WebApplicationException.class,
                () -> RecruitmentResource.requireTypedFullName(null, null, ""));
        assertThrows(WebApplicationException.class,
                () -> RecruitmentResource.requireTypedFullName("", "", ""));
    }
}

package dk.trustworks.intranet.competenceservice.resources;

import dk.trustworks.intranet.competenceservice.dto.SettingsUpdateRequest;
import dk.trustworks.intranet.competenceservice.services.CompetenceContentService;
import dk.trustworks.intranet.competenceservice.services.CompetenceMatrixService;
import dk.trustworks.intranet.competenceservice.services.CompetenceSettingsService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A request that carries no {@code X-Requested-By} must not be able to author anything.
 *
 * <p>The JWT on these paths is a client-credentials token with no user in it (§10.4) — the
 * subject travels only in the header — so "no header" means the module cannot name who is
 * acting. On the {@code competence:approve} half that already failed closed for free:
 * {@code AuthorizationServiceImpl.resolveReach} answers {@code none()} for a null actor, so the
 * matrix, the queue and both CSV exports return nothing. <strong>The {@code competence:write}
 * half has no reach to fail closed on.</strong> Without the guard the same header-less request
 * publishes content company-wide, writes NULL into {@code published_by} — the evidence column
 * §10.6 exists for — and leaves {@code actor=null} in the only other record of it.
 *
 * <p>The assertions therefore pair the 400 with "the service was never reached": a guard that
 * threw after the write would be no guard at all, and this is the ordering the learner resource
 * has had from the start.
 */
class CompetenceAdminActorTest {

    private CompetenceAdminResource resource;
    private CompetenceMatrixService matrixService;
    private CompetenceContentService contentService;
    private CompetenceSettingsService settingsService;
    private RequestHeaderHolder headerHolder;

    @BeforeEach
    void setUp() {
        matrixService = mock(CompetenceMatrixService.class);
        contentService = mock(CompetenceContentService.class);
        settingsService = mock(CompetenceSettingsService.class);
        headerHolder = new RequestHeaderHolder();

        resource = new CompetenceAdminResource();
        resource.matrixService = matrixService;
        resource.contentService = contentService;
        resource.settingsService = settingsService;
        resource.requestHeaderHolder = headerHolder;
    }

    @Test
    @DisplayName("publishing without X-Requested-By is a 400, and nothing is published")
    void publishWithoutHeaderIsRefused() {
        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.publish("requirement-uuid", "course", null));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), thrown.getResponse().getStatus(),
                "400, not 401: the API client authenticated correctly and the request is simply "
                        + "incomplete — a 401 sends the BFF into a token refresh that cannot fix it");
        assertTrue(thrown.getMessage().contains("X-Requested-By"), thrown.getMessage());
        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("exporting the answer key without X-Requested-By is a 400, and nothing is exported")
    void contentExportWithoutHeaderIsRefused() {
        assertThrows(WebApplicationException.class, () -> resource.exportContent(null));
        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("changing a setting without X-Requested-By is a 400, and nothing is changed")
    void settingsWriteWithoutHeaderIsRefused() {
        assertThrows(WebApplicationException.class,
                () -> resource.updateSettings(
                        new SettingsUpdateRequest(Map.of("competence.cadence-days", "1"))));
        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("a blank header is treated as no header")
    void blankHeaderIsRefused() {
        headerHolder.setUserUuid("   ");
        assertThrows(WebApplicationException.class, () -> resource.matrix());
        verifyNoInteractions(matrixService);
    }

    @Test
    @DisplayName("with the header present the actor reaches the service unchanged")
    void headerPresentPassesTheActorThrough() {
        headerHolder.setUserUuid("5c2e77a1-user-0000-0000-000000000001");

        resource.matrix();

        verify(matrixService).matrix("5c2e77a1-user-0000-0000-000000000001");
    }

    /**
     * Reads that carry no evidence and no write are deliberately left alone — narrowing them
     * would be a behaviour change this fix has no reason to make. Asserted so that the guard's
     * scope is a decision on the record rather than an accident of which methods call
     * {@code actor()}.
     */
    @Test
    @DisplayName("a header-less read of the version history is still served")
    void readsAreNotNarrowedByTheGuard() {
        resource.versions("requirement-uuid");
        verify(contentService).versionHistory("requirement-uuid");
    }


    @Test
    @DisplayName("an unknown content kind is still a 400 before anything else happens")
    void unknownKindStillRefused() {
        headerHolder.setUserUuid("5c2e77a1-user-0000-0000-000000000001");
        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.publish("requirement-uuid", "quiz", null));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(contentService);
    }
}

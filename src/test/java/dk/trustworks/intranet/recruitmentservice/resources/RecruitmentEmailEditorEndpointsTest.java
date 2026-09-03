package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.PreviewTemplateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RenderedEmailResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TemplateCoverageResponse;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The endpoints the communications page adds: the live preview, "send a
 * test to me", and the Journey tab's coverage read model.
 *
 * <p>The one that has to be right is the test send. It has <b>no recipient
 * field</b> and must never grow one: the address is the calling user's own,
 * resolved server-side, because an endpoint that let a recruiter name the
 * recipient would be an authenticated open relay wearing the company's
 * SES-verified sender identity. The consequence pinned here is the awkward
 * one — a caller with no address on file has nowhere to send to, and gets a
 * 400 that says so rather than a silent success.
 *
 * <p>Database-free: the resource's collaborators are injected fields, so the
 * decision logic runs directly against test doubles with no Quarkus boot.
 */
class RecruitmentEmailEditorEndpointsTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final String OWN_ADDRESS = "hans.lassen@trustworks.dk";

    private RecruitmentEmailResource resource;
    private RecruitmentEmailService emailService;
    private RecruitmentVisibility visibility;
    private RecruitmentFeatureFlag featureFlag;
    private RecruitmentCommsCoverageService coverageService;

    @BeforeEach
    void setUp() {
        emailService = mock(RecruitmentEmailService.class);
        visibility = mock(RecruitmentVisibility.class);
        featureFlag = mock(RecruitmentFeatureFlag.class);
        coverageService = mock(RecruitmentCommsCoverageService.class);

        RequestHeaderHolder headers = new RequestHeaderHolder();
        headers.setUserUuid(ACTOR.toString());

        resource = new RecruitmentEmailResource();
        resource.emailService = emailService;
        resource.visibility = visibility;
        resource.featureFlag = featureFlag;
        resource.requestHeaderHolder = headers;
        resource.scopeContext = mock(ScopeContext.class);
        resource.coverageService = coverageService;

        when(featureFlag.isInterviewsEnabled()).thenReturn(true);
        when(visibility.isRecruiterTier(ACTOR.toString())).thenReturn(true);
    }

    // ---- Test send ---------------------------------------------------------

    @Test
    void aCallerWithNoAddressOnFile_isRefusedWith400_andNothingIsQueued() {
        when(emailService.ownAddress(ACTOR.toString())).thenReturn(null);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.testSendTemplate(TEMPLATE));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                thrown.getResponse().getStatus());
        assertTrue(thrown.getMessage().contains("no email address on file"),
                "the message has to say why: " + thrown.getMessage());
        verify(emailService, never()).sendTest(anyString(), anyString(), anyString());
    }

    @Test
    void aTestSend_goesToTheCallersOwnAddress_and202s() {
        when(emailService.ownAddress(ACTOR.toString())).thenReturn(OWN_ADDRESS);
        when(emailService.sendTest(TEMPLATE.toString(), OWN_ADDRESS, ACTOR.toString()))
                .thenReturn("mail-uuid");

        Response response = resource.testSendTemplate(TEMPLATE);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
        verify(emailService).sendTest(TEMPLATE.toString(), OWN_ADDRESS, ACTOR.toString());
    }

    @Test
    void aTestSendOfAnUnknownTemplate_is404() {
        when(emailService.ownAddress(ACTOR.toString())).thenReturn(OWN_ADDRESS);
        when(emailService.sendTest(anyString(), anyString(), anyString())).thenReturn(null);

        assertThrows(NotFoundException.class, () -> resource.testSendTemplate(TEMPLATE));
    }

    @Test
    void aNonRecruiter_cannotTestSend_andIsNotToldTheEndpointExists() {
        // 404-not-403, the sibling convention. Checked before the address
        // lookup so a teamlead cannot probe who has an address on file.
        when(visibility.isRecruiterTier(ACTOR.toString())).thenReturn(false);

        assertThrows(NotFoundException.class, () -> resource.testSendTemplate(TEMPLATE));
        verify(emailService, never()).ownAddress(anyString());
    }

    // ---- Preview -----------------------------------------------------------

    @Test
    void preview_returnsWhatTheServiceRendered() {
        when(emailService.preview(eq("Hej {{candidate_first_name}}"), eq("Body"), any()))
                .thenReturn(new RecruitmentEmailRenderer.Rendered(
                        "Hej Anna", "Body", Set.of("consent_link")));

        RenderedEmailResponse response = resource.previewTemplate(new PreviewTemplateRequest(
                "Hej {{candidate_first_name}}", "Body", "PLAIN", "ACKNOWLEDGEMENT"));

        assertEquals("Hej Anna", response.subject());
        assertEquals("Body", response.body());
        assertEquals("PLAIN", response.bodyFormat());
        assertEquals(List.of("consent_link"), response.unresolvedFields());
    }

    @Test
    void previewingAnEmptyDraft_is400() {
        // The same explicit input caps every other write on this resource
        // applies — @Valid is inert in this repo (§P4.9).
        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.previewTemplate(
                        new PreviewTemplateRequest("Emne", "  ", "PLAIN", null)));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                thrown.getResponse().getStatus());
    }

    @Test
    void previewingAnEmptyRichTextDraft_is400_too() {
        // "<p><br></p>" is what an empty rich editor serialises to, and
        // String.isBlank() calls it non-empty.
        assertThrows(WebApplicationException.class,
                () -> resource.previewTemplate(
                        new PreviewTemplateRequest("Emne", "<p><br></p>", "HTML", null)));
    }

    // ---- Coverage ----------------------------------------------------------

    @Test
    void coverage_isOnTheHiringTier_likeTheTemplateListItIsReadAlongside() {
        // Deliberate, and the one place this page departs from "everything
        // template-shaped is recruiter-tier": coverage is the READ half of
        // the communications page, its sibling template list is already on
        // the hiring tier, and the FE contract puts the BFF route there too.
        // Recruiter tier here would 404 a teamlead halfway through the page.
        TemplateCoverageResponse expected = new TemplateCoverageResponse(List.of());
        when(visibility.isRecruiterTier(ACTOR.toString())).thenReturn(false);
        when(visibility.canEmailCandidates(ACTOR.toString())).thenReturn(true);
        when(coverageService.coverage()).thenReturn(expected);

        assertSame(expected, resource.coverage());
    }

    @Test
    void aCallerBelowTheHiringTier_isNotToldTheCoverageEndpointExists() {
        when(visibility.canEmailCandidates(ACTOR.toString())).thenReturn(false);

        assertThrows(NotFoundException.class, () -> resource.coverage());
        verify(coverageService, never()).coverage();
    }
}

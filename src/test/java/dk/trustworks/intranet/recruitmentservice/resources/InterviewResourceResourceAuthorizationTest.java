package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.model.InterviewResource;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.InterviewResourceService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Direct-resource lock for the per-person gate behind the BFF role tier. */
class InterviewResourceResourceAuthorizationTest {

    private InterviewResourceResource resource;
    private InterviewResourceService service;
    private RequestHeaderHolder headers;
    private RecruitmentVisibility visibility;

    @BeforeEach
    void setUp() {
        resource = new InterviewResourceResource();
        service = mock(InterviewResourceService.class);
        headers = mock(RequestHeaderHolder.class);
        visibility = mock(RecruitmentVisibility.class);
        resource.interviewResourceService = service;
        resource.requestHeaderHolder = headers;
        resource.visibility = visibility;
    }

    @Test
    void nonRecruiterCannotMutateTheSharedLibraryThroughDirectBackendCalls() {
        when(headers.getUserUuid()).thenReturn("assistant-only");
        when(visibility.isRecruiterTier("assistant-only")).thenReturn(false);

        assertForbidden(() -> resource.create(new InterviewResourceResource.CreateRequest(
                "Case", "CASE", null, "case.pdf", "application/pdf", "YQ==")));
        assertForbidden(() -> resource.update("resource-1",
                new InterviewResourceResource.UpdateRequest("Changed", null, null)));
        assertForbidden(() -> resource.delete("resource-1"));

        verifyNoInteractions(service);
    }

    @Test
    void recruiterTierStillReachesEverySharedLibraryMutation() {
        when(headers.getUserUuid()).thenReturn("recruiter");
        when(visibility.isRecruiterTier("recruiter")).thenReturn(true);
        InterviewResource stored = new InterviewResource();
        when(service.create(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(stored);
        when(service.update(anyString(), any(), any(), any())).thenReturn(stored);

        assertEquals(201, resource.create(new InterviewResourceResource.CreateRequest(
                "Case", "CASE_MATERIAL", null, "case.pdf", "application/pdf", "YQ=="))
                .getStatus());
        assertSame(stored, resource.update("resource-1",
                new InterviewResourceResource.UpdateRequest("Changed", null, null)));
        assertEquals(204, resource.delete("resource-1").getStatus());

        verify(service).softDelete("resource-1");
        verify(visibility, never()).canWriteDossier(anyString());
    }

    private static void assertForbidden(Runnable action) {
        WebApplicationException thrown = assertThrows(WebApplicationException.class, action::run);
        assertEquals(403, thrown.getResponse().getStatus());
    }
}

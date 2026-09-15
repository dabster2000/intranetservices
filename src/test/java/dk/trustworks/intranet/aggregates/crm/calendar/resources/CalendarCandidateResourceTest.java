package dk.trustworks.intranet.aggregates.crm.calendar.resources;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService.*;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarCandidateResourceTest {
    final CalendarCandidateResource resource = new CalendarCandidateResource();
    final String actor = "00000000-0000-4000-8000-000000000001";
    CalendarCandidateResourceTest() {
        resource.candidates = mock(CalendarCandidateService.class);
        resource.headers = mock(RequestHeaderHolder.class);
        when(resource.headers.getUserUuid()).thenReturn(actor);
    }

    @Test void committedStarRemainsSuccessfulWhenDerivedRefreshFails() {
        ReviewDTO expected = new ReviewDTO("STARRED", "person");
        when(resource.candidates.review(eq("client"), eq("candidate"), any(), eq(actor))).thenReturn(expected);
        doThrow(new IllegalStateException("synthetic unavailable state")).when(resource.candidates)
                .requestFullReadForCandidate("client", "candidate");
        assertEquals(expected, resource.review("client", "candidate", new ReviewRequest("STAR")));
    }

    @Test void impersonationAndMissingHumanIdentityCannotWrite() {
        when(resource.headers.isImpersonated()).thenReturn(true);
        assertEquals(403, assertThrows(WebApplicationException.class,
                () -> resource.review("client", "candidate", new ReviewRequest("STAR"))).getResponse().getStatus());
        when(resource.headers.isImpersonated()).thenReturn(false);
        when(resource.headers.getUserUuid()).thenReturn("anonymous");
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> resource.review("client", "candidate", new ReviewRequest("STAR"))).getResponse().getStatus());
        verifyNoInteractions(resource.candidates);
    }

    @Test void readsAndWritesRequireTheirOwnScopes() throws Exception {
        assertArrayEquals(new String[]{"accounts:read"}, CalendarCandidateResource.class.getAnnotation(RolesAllowed.class).value());
        assertArrayEquals(new String[]{"accounts:write"}, CalendarCandidateResource.class
                .getMethod("review", String.class, String.class, ReviewRequest.class).getAnnotation(RolesAllowed.class).value());
    }
}

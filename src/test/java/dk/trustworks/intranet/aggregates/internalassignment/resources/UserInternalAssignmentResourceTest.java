package dk.trustworks.intranet.aggregates.internalassignment.resources;

import dk.trustworks.intranet.aggregates.internalassignment.services.InternalAssignmentService;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The range contract of {@code GET /users/{useruuid}/internal-assignments}: both dates
 * required and ISO, {@code todate} not before {@code fromdate}, and at most
 * {@link UserInternalAssignmentResource#MAX_RANGE_DAYS} apart. The profile asks for three
 * months back to a year ahead in one call, so the cap must stay well above that. The method
 * moved verbatim out of the pre-split class; this pins that the move changed nothing.
 */
class UserInternalAssignmentResourceTest {

    private static final String SUBJECT = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b";

    private UserInternalAssignmentResource resource;
    private InternalAssignmentService service;

    @BeforeEach
    void setUp() {
        resource = new UserInternalAssignmentResource();
        resource.scope = mock(ScopeGuard.class);
        service = mock(InternalAssignmentService.class);
        resource.service = service;
        when(service.listForUser(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void aProfileSizedRange_isPassedThroughToTheService() {
        resource.list(SUBJECT, "2026-06-01", "2027-09-30");
        verify(service).listForUser(SUBJECT, LocalDate.of(2026, 6, 1), LocalDate.of(2027, 9, 30));
    }

    @Test
    void exactlyMaxRangeDays_isStillAccepted() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(UserInternalAssignmentResource.MAX_RANGE_DAYS);
        resource.list(SUBJECT, from.toString(), to.toString());
        verify(service).listForUser(SUBJECT, from, to);
    }

    @Test
    void oneDayPastMaxRange_isBadRequest() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        String to = from.plusDays(UserInternalAssignmentResource.MAX_RANGE_DAYS + 1).toString();
        assertBadRequest(() -> resource.list(SUBJECT, from.toString(), to), "Invalid range");
        verifyNoInteractions(service);
    }

    @Test
    void todateBeforeFromdate_isBadRequest() {
        assertBadRequest(() -> resource.list(SUBJECT, "2026-09-30", "2026-09-01"), "Invalid range");
        verifyNoInteractions(service);
    }

    @Test
    void missingOrMalformedDates_areBadRequest() {
        assertBadRequest(() -> resource.list(SUBJECT, null, "2026-09-30"), "fromdate is required (yyyy-MM-dd)");
        assertBadRequest(() -> resource.list(SUBJECT, "2026-09-01", " "), "todate is required (yyyy-MM-dd)");
        assertBadRequest(() -> resource.list(SUBJECT, "01-09-2026", "2026-09-30"),
                "fromdate must be an ISO date (yyyy-MM-dd)");
        verifyNoInteractions(service);
    }

    @Test
    void reachIsCheckedBeforeTheDatesAreEvenParsed() {
        doThrow(new WebApplicationException("Internal assignments outside your reach", 403))
                .when(resource.scope).requireSubjectWhenActor(eq("users:read"), eq(SUBJECT), any());
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.list(SUBJECT, null, null));
        assertEquals(403, e.getResponse().getStatus());
        verifyNoInteractions(service);
    }

    private static void assertBadRequest(Executable call, String message) {
        WebApplicationException e = assertThrows(WebApplicationException.class, call);
        assertEquals(400, e.getResponse().getStatus());
        assertEquals(message, e.getMessage());
    }
}

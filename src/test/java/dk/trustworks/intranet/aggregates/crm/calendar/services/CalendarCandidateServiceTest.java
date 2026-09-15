package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarCandidateServiceTest {
    CalendarCandidateService service;
    EntityManager em;
    Query query;
    final LocalDateTime from = LocalDateTime.of(2025, 9, 15, 0, 0);
    final LocalDateTime to = from.plusYears(1);

    @BeforeEach void setup() {
        service = new CalendarCandidateService();
        service.em = em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
    }

    PendingCandidate pending(String user, String reason, String email) {
        return new PendingCandidate("client", user, "event", "ical", from.plusMonths(3), "series",
                reason, List.of(new Attendee(email, "Client Person", "client.example")));
    }

    @Test void completeReadReconcilesOnlyTheMailboxGenerationAndWindow() {
        service.record("user", 4, List.of(pending("user", "RECURRING", "Person@client.example")), from, to, true);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().getLast().contains("user_uuid=:user"));
        assertTrue(sql.getAllValues().getLast().contains("occurred_at between :from and :to"));
        assertTrue(sql.getAllValues().getLast().contains("sync_generation<>:generation"));
        verify(query).setParameter("email", "person@client.example");
        verify(query, times(2)).setParameter("generation", 4L);
    }

    @Test void incompleteReadUpsertsWithoutPruning() {
        service.record("user", 8, List.of(pending("user", "DELIVERY", "person@client.example")), from, to, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().startsWith("insert into account_calendar_candidate"));
        assertTrue(sql.getValue().contains("on duplicate key update"));
    }

    @Test void crossMailboxAndUnknownReasonCannotBePersisted() {
        assertThrows(IllegalArgumentException.class, () -> service.record("user", 1,
                List.of(pending("other-user", "RECURRING", "person@client.example")), from, to, true));
        assertThrows(IllegalArgumentException.class, () -> service.record("user", 1,
                List.of(pending("user", "PRIVATE", "person@client.example")), from, to, true));
        verifyNoInteractions(em);
    }

    @Test void malformedEmailIsNotMaterialized() {
        service.record("user", 1, List.of(pending("user", "DELIVERY", "not an email")), from, to, false);
        verifyNoInteractions(em);
        assertNull(CalendarCandidateService.normalizeEmail("bad\n@client.example"));
    }

    @Test void candidateIdentityIsStableAndAccountEmailReasonScoped() {
        String uuid = CalendarCandidateService.candidateUuid("a", "u", "e", "person@example.com", "RECURRING");
        assertEquals(uuid, CalendarCandidateService.candidateUuid("a", "u", "e", "person@example.com", "RECURRING"));
        assertNotEquals(uuid, CalendarCandidateService.candidateUuid("b", "u", "e", "person@example.com", "RECURRING"));
        assertNotEquals(uuid, CalendarCandidateService.candidateUuid("a", "u", "e", "other@example.com", "RECURRING"));
        assertNotEquals(uuid, CalendarCandidateService.candidateUuid("a", "u", "e", "person@example.com", "DELIVERY"));
        assertNotEquals(CalendarCandidateService.candidateUuid("a|b", "c", "e", "p@x.com", "DELIVERY"),
                CalendarCandidateService.candidateUuid("a", "b|c", "e", "p@x.com", "DELIVERY"));
    }

    @Test void sharedAddressesAndSharedReasonCannotBeStarred() {
        for (String email : List.of("noreply-meeting-booking@client.example", "sg.it@client.example")) {
            assertEquals(409, assertThrows(WebApplicationException.class,
                    () -> CalendarCandidateService.requirePersonalAddress(email, "RECURRING")).getResponse().getStatus());
        }
        assertThrows(WebApplicationException.class,
                () -> CalendarCandidateService.requirePersonalAddress("named@client.example", "SHARED_ADDRESS"));
        assertDoesNotThrow(() -> CalendarCandidateService.requirePersonalAddress("sara@client.example", "DELIVERY"));
    }

    @Test void missingOrUnknownReviewActionsAreBadRequests() {
        for (ReviewRequest request : java.util.Arrays.asList(null, new ReviewRequest(null), new ReviewRequest("UNKNOWN"))) {
            assertEquals(400, assertThrows(WebApplicationException.class,
                    () -> service.review("client", "candidate", request, "actor")).getResponse().getStatus());
        }
        verifyNoInteractions(em);
    }
}

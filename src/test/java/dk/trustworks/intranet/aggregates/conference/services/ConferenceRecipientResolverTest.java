package dk.trustworks.intranet.aggregates.conference.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConferenceRecipientResolverTest {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0);

    private ConferenceRecipientResolver.Snapshot snapshot(String row, String participant, String email, String name, LocalDateTime registered) {
        return new ConferenceRecipientResolver.Snapshot(row, participant, email, name, registered);
    }

    @Test
    void latestSnapshotUsesImmutableStableIdentityAndLowestUuidRepresentative() {
        var recipients = ConferenceRecipientResolver.resolveSnapshots(List.of(B, A, B), List.of(
                snapshot("old", A, "old@example.com", "old", NOW.minusDays(1)),
                snapshot("new", A, " Same@Example.com ", "Alpha", NOW),
                snapshot("other", B, "same@example.com", "Beta", NOW)));
        assertEquals(1, recipients.size());
        assertEquals(A, recipients.getFirst().participantUuid());
        assertEquals("Same@Example.com", recipients.getFirst().email());
        assertEquals("same@example.com", recipients.getFirst().normalizedEmail());
        assertEquals("Alpha", recipients.getFirst().name());
    }

    @Test
    void latestConflictingAddressesRejectRatherThanChooseRandomRecipient() {
        var error = assertThrows(WebApplicationException.class, () -> ConferenceRecipientResolver.resolveSnapshots(List.of(A), List.of(
                snapshot("one", A, "one@example.com", "One", NOW),
                snapshot("two", A, "two@example.com", "Two", NOW))));
        assertEquals(409, error.getResponse().getStatus());
    }

    @Test
    void tiedSameAddressHasDeterministicSnapshotAndNullDatesAreSupported() {
        var recipients = ConferenceRecipientResolver.resolveSnapshots(List.of(A), List.of(
                snapshot("b", A, "same@example.com", "Second", null),
                snapshot("a", A, "SAME@example.com", "First", null)));
        assertEquals("First", recipients.getFirst().name());
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> ConferenceRecipientResolver.resolveSnapshots(List.of(B), List.of())).getResponse().getStatus());
    }
}

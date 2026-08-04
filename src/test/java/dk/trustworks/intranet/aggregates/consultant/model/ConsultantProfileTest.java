package dk.trustworks.intranet.aggregates.consultant.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ConsultantProfileTest {

    @Test
    void isStale_whenNeverGenerated_returnsTrue() {
        var profile = new ConsultantProfile("user-uuid");
        assertTrue(profile.isStale(null));
    }

    @Test
    void isStale_whenGeneratedRecently_returnsFalse() {
        var profile = new ConsultantProfile("user-uuid");
        var cvUpdatedAt = LocalDateTime.now().minusDays(1);
        profile.updateFrom("pitch", "[]", "[]", cvUpdatedAt);

        assertFalse(profile.isStale(cvUpdatedAt));
    }

    @Test
    void isStale_whenGeneratedMoreThan7DaysAgo_returnsTrue() {
        var profile = new ConsultantProfile("user-uuid");
        var cvUpdatedAt = LocalDateTime.now().minusDays(10);
        profile.updateFrom("pitch", "[]", "[]", cvUpdatedAt);
        // Force generatedAt to 8 days ago
        profile.setGeneratedAt(LocalDateTime.now().minusDays(8));

        assertTrue(profile.isStale(cvUpdatedAt));
    }

    @Test
    void isStale_whenCvUpdatedSinceGeneration_returnsTrue() {
        var profile = new ConsultantProfile("user-uuid");
        var oldCvUpdated = LocalDateTime.now().minusDays(2);
        profile.updateFrom("pitch", "[]", "[]", oldCvUpdated);

        var newCvUpdated = LocalDateTime.now().minusDays(1);
        assertTrue(profile.isStale(newCvUpdated));
    }

    @Test
    void isStale_whenCvUpdatedAtIsNull_andProfileHasCvUpdatedAt_returnsFalse() {
        var profile = new ConsultantProfile("user-uuid");
        var cvUpdatedAt = LocalDateTime.now().minusDays(1);
        profile.updateFrom("pitch", "[]", "[]", cvUpdatedAt);

        // currentCvUpdatedAt is null => no CV change detected
        assertFalse(profile.isStale(null));
    }

    @Test
    void updateFrom_setsAllFieldsAndGeneratedAt() {
        var profile = new ConsultantProfile("user-uuid");
        var cvUpdatedAt = LocalDateTime.now();

        profile.updateFrom("Great pitch", "[\"Finance\"]", "[\"Java\"]", cvUpdatedAt);

        assertEquals("Great pitch", profile.getPitchText());
        assertEquals("[\"Finance\"]", profile.getIndustriesJson());
        assertEquals("[\"Java\"]", profile.getTopSkillsJson());
        assertEquals(cvUpdatedAt, profile.getCvUpdatedAt());
        assertNotNull(profile.getGeneratedAt());
    }

    @Test
    void constructor_requiresNonNullUseruuid() {
        assertThrows(NullPointerException.class, () -> new ConsultantProfile(null));
    }

    // ------------------------------------------------------------------------
    // Generation-state gate (V461). isStale() alone is not a retry decision: a
    // FAILED generation deliberately leaves generatedAt NULL so a broken profile
    // is never served as fresh, which makes isStale() permanently true. Without
    // shouldAttempt() every dashboard load would re-fire OpenAI for a consultant
    // that cannot succeed.
    // ------------------------------------------------------------------------

    @Test
    void newProfile_defaultsToPending_soAPanachePersistCannotWriteNullStatus() {
        // The column is NOT NULL with a DEFAULT, but a column default does not apply to an
        // explicit NULL — Hibernate would emit one for an uninitialised field.
        assertEquals(ConsultantProfile.STATUS_PENDING, new ConsultantProfile("user-uuid").getStatus());
    }

    @Test
    void shouldAttempt_whenParkedAsUnavailable_returnsFalse() {
        var profile = new ConsultantProfile("user-uuid");
        profile.setStatus(ConsultantProfile.STATUS_UNAVAILABLE);
        // Even with no attempt on record and an elapsed backoff, a parked row stays parked.
        profile.setLastAttemptAt(null);

        assertFalse(profile.shouldAttempt(30));
    }

    @Test
    void shouldAttempt_whenNeverAttempted_returnsTrue() {
        var profile = new ConsultantProfile("user-uuid");

        assertNull(profile.getLastAttemptAt());
        assertTrue(profile.shouldAttempt(30));
    }

    @Test
    void shouldAttempt_isFalseInsideTheBackoffWindow_andTrueOutsideIt() {
        var profile = new ConsultantProfile("user-uuid");

        profile.setLastAttemptAt(LocalDateTime.now().minusMinutes(5));
        assertFalse(profile.shouldAttempt(30), "a retry 5 minutes after a failure would be a request-rate model storm");

        profile.setLastAttemptAt(LocalDateTime.now().minusMinutes(31));
        assertTrue(profile.shouldAttempt(30));
    }

    @Test
    void updateFrom_clearsTheFailureBookkeepingAndMarksTheRowReady() {
        var profile = new ConsultantProfile("user-uuid");
        profile.setStatus(ConsultantProfile.STATUS_UNAVAILABLE);
        profile.setGenerationAttempts(3);
        profile.setLastError("empty-output");

        profile.updateFrom("Great pitch", "[\"Finance\"]", "[\"Java\"]", LocalDateTime.now());

        assertEquals(ConsultantProfile.STATUS_READY, profile.getStatus());
        assertEquals(0, profile.getGenerationAttempts());
        assertNull(profile.getLastError());
    }
}

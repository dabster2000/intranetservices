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
}

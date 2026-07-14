package dk.trustworks.intranet.aggregates.users.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServicePracticeAttributionTest {

    @Test
    void creationFallbackRunsOnlyForNonNullPractice() {
        assertFalse(UserService.shouldRecordCreatedPractice(null));
        assertTrue(UserService.shouldRecordCreatedPractice("PM"));
    }

    @Test
    void updateFallbackRunsOnlyForActualPracticeChange() {
        assertFalse(UserService.shouldRecordPracticeChange("PM", "PM"));
        assertFalse(UserService.shouldRecordPracticeChange(null, null));
        assertTrue(UserService.shouldRecordPracticeChange("PM", "BA"));
        assertTrue(UserService.shouldRecordPracticeChange("PM", null));
    }
}

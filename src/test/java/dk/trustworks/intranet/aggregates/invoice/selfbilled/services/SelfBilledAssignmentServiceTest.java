package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelfBilledAssignmentServiceTest {

    @Test
    void assignment_period_rejects_missing_or_impossible_years() {
        assertThrows(WebApplicationException.class,
                () -> SelfBilledAssignmentService.requireAssignmentPeriod(null, 8));
        assertThrows(WebApplicationException.class,
                () -> SelfBilledAssignmentService.requireAssignmentPeriod(0, 8));
        assertThrows(WebApplicationException.class,
                () -> SelfBilledAssignmentService.requireAssignmentPeriod(10000, 8));
    }

    @Test
    void assignment_period_rejects_missing_or_impossible_months() {
        assertThrows(WebApplicationException.class,
                () -> SelfBilledAssignmentService.requireAssignmentPeriod(2025, null));
        assertThrows(WebApplicationException.class,
                () -> SelfBilledAssignmentService.requireAssignmentPeriod(2025, 0));
        assertThrows(WebApplicationException.class,
                () -> SelfBilledAssignmentService.requireAssignmentPeriod(2025, 13));
    }

    @Test
    void assignment_period_accepts_plausible_work_period() {
        assertDoesNotThrow(() -> SelfBilledAssignmentService.requireAssignmentPeriod(2025, 8));
    }
}

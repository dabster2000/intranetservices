package dk.trustworks.intranet.expenseservice.dto;

import dk.trustworks.intranet.expenseservice.dto.RuleOverrideStatsDTO.Entry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuleOverrideStatsDTOTest {

    @Test
    void override_rate_is_overridden_over_blocked() {
        Entry e = Entry.of("R_OFFICE_FOOD_DRINK", 40, 20, 19, LocalDateTime.of(2026, 8, 1, 12, 0));
        assertEquals(0.95, e.overrideRate(), 1e-9);
        assertEquals(40, e.firings());
        assertEquals(20, e.blockedExpenses());
        assertEquals(19, e.overriddenExpenses());
    }

    @Test
    void zero_blocked_expenses_yields_zero_rate_not_division_error() {
        Entry e = Entry.of("R_NEVER_FIRED", 0, 0, 0, null);
        assertEquals(0.0, e.overrideRate(), 1e-9);
        assertNull(e.lastFiredAt());
    }

    @Test
    void full_override_yields_rate_one() {
        Entry e = Entry.of("R_WEEKEND_FOOD_DRINK", 7, 7, 7, LocalDateTime.of(2026, 8, 10, 9, 30));
        assertEquals(1.0, e.overrideRate(), 1e-9);
    }
}

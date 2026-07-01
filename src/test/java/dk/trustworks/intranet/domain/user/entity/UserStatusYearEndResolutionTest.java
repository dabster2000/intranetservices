package dk.trustworks.intranet.domain.user.entity;

import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pure unit tests — no Quarkus, no DB.
 *
 * <p>Regression guard for the "Din del af Trustworks" (your-part-of-trustworks)
 * outage where every employee showed up excluded / 0 DKK. The basis endpoint
 * ({@code YourPartOfTrustworksResource#findMonthlyBasis}) computes
 * {@code terminatedBeforeYearEnd = user.getUserStatus(fiscalYearEnd).getStatus() == TERMINATED}.
 *
 * <p>{@link User#getUserStatus(LocalDate)} resolves the latest status at or before
 * the date and, when NONE is found, falls back to a synthetic {@code TERMINATED}
 * status. Because {@code User.statuses} is {@code @Transient}, a bare
 * {@code User.list("uuid in ?1", ...)} leaves it empty — so the fallback fired for
 * EVERY user and the whole grid was excluded. The fix batch-loads {@code UserStatus}
 * and attaches it before the check; these tests pin both the correct resolution and
 * the dangerous empty-list fallback so the trap can't silently return.
 */
class UserStatusYearEndResolutionTest {

    /** FY2024/2025 ends here — the value the resource passes to getUserStatus(). */
    private static final LocalDate FISCAL_YEAR_END = LocalDate.of(2025, 6, 30);

    private static User userWithStatuses(UserStatus... statuses) {
        User user = new User();
        user.setStatuses(new ArrayList<>(List.of(statuses)));
        return user;
    }

    private static UserStatus status(StatusType type, LocalDate date) {
        return new UserStatus(ConsultantType.CONSULTANT, type, date, 100, "user-uuid");
    }

    /** Mirrors the resource's flag exactly so the test tracks real behaviour. */
    private static boolean terminatedBeforeYearEnd(User user) {
        return user.getUserStatus(FISCAL_YEAR_END).getStatus() == StatusType.TERMINATED;
    }

    @Test
    void activeStatusLoaded_isNotTerminatedAtYearEnd() {
        // An ordinary active employee hired well before year-end: must be INCLUDED.
        User user = userWithStatuses(status(StatusType.ACTIVE, LocalDate.of(2024, 8, 1)));

        assertEquals(StatusType.ACTIVE, user.getUserStatus(FISCAL_YEAR_END).getStatus());
        assertNotEquals(true, terminatedBeforeYearEnd(user),
                "active employee with loaded statuses must not be flagged terminated-before-year-end");
    }

    @Test
    void emptyStatuses_fallBackToTerminated_regressionGuard() {
        // THE BUG: a bare User.list(...) leaves @Transient statuses empty. The
        // getUserStatus() fallback then returns a synthetic TERMINATED for everyone,
        // which excluded the entire your-part-of-trustworks grid (0 DKK).
        User user = userWithStatuses(); // no statuses loaded

        assertEquals(StatusType.TERMINATED, user.getUserStatus(FISCAL_YEAR_END).getStatus(),
                "empty statuses must resolve to the synthetic TERMINATED fallback — the resource MUST load statuses first");
    }

    @Test
    void terminationAfterYearEnd_isStillActiveAtYearEnd() {
        // Left the company AFTER the fiscal year ended → still ACTIVE as of year-end.
        User user = userWithStatuses(
                status(StatusType.ACTIVE, LocalDate.of(2024, 8, 1)),
                status(StatusType.TERMINATED, LocalDate.of(2025, 9, 1)));

        assertEquals(StatusType.ACTIVE, user.getUserStatus(FISCAL_YEAR_END).getStatus(),
                "a termination dated after year-end must not retroactively exclude the employee");
    }

    @Test
    void terminationBeforeYearEnd_isTerminated() {
        // Genuinely left before year-end → correctly excluded (the feature's intent).
        User user = userWithStatuses(
                status(StatusType.ACTIVE, LocalDate.of(2023, 1, 1)),
                status(StatusType.TERMINATED, LocalDate.of(2025, 3, 1)));

        assertEquals(StatusType.TERMINATED, user.getUserStatus(FISCAL_YEAR_END).getStatus());
        assertEquals(true, terminatedBeforeYearEnd(user),
                "an employee terminated before year-end stays excluded by default");
    }
}

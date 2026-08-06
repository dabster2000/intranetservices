package dk.trustworks.intranet.apigateway.dto;

import dk.trustworks.intranet.domain.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Day-based monthly foundation per user for a fiscal year (Jul -> Jun). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeBonusBasisDTO {
    /**
     * Phase 9.3 (owner decision 2026-08-06, access-intent Decision 8): the full
     * {@code User} entity used to ride along here, putting phone, birthday,
     * gender and pension details on a payload whose consumers render a name and
     * an eligibility flag. Only the fields the bonus grid and the "my part"
     * views actually use survive.
     */
    private BasisUser user;
    private int year; // fiscal start year (July of this calendar year)
    private List<MonthBasis> months; // 12 entries, covering Jul..Jun in calendar order (Jul..Dec of 'year', Jan..Jun of 'year'+1)
    /** True when the user's effective employment status at fiscal year-end (Jun 30) is TERMINATED — the frontend defaults these employees to not eligible. */
    private boolean terminatedBeforeYearEnd;
    /** Per-month career multipliers (12 entries, fiscal order Jul..Jun): 0/1/1.5/2/3. */
    private double[] monthMultipliers;
    /** True when every month is 0× (not eligible) while the user still has salary weight — never overridable in the UI. */
    private boolean careerIneligible;
    /** Display-only representative career level name (§3), or "" when none applies. */
    private String representativeCareerLevel;

    /**
     * The bonus-basis projection of a user — identity only, nothing personal.
     * Deliberately NO {@code twBonusEligible}: the {@code User} entity never had
     * that property, so the old payload never carried it either — the dashboard
     * consumer reads it as {@code undefined} today (latent defect, recorded in
     * findings 2026-08-06). Inventing the field here would silently flip that
     * consumer's eligibility logic — a behaviour change this phase must not make.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BasisUser {
        private String uuid;
        private String username;
        private String firstname;
        private String lastname;

        public static BasisUser from(User user) {
            return new BasisUser(user.getUuid(), user.getUsername(),
                    user.getFirstname(), user.getLastname());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthBasis {
        private int year;                 // calendar year of the month
        private int month;                // 1..12
        private double eligibleShare;     // 0..1 = eligibleDays / daysInMonth
        private double avgSalary;         // sum(dailySalary)/daysInMonth
        private double weightedAvgSalary; // sum(dailySalary * eligibleFlag)/daysInMonth
    }
}

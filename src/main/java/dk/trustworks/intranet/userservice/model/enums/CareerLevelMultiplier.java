package dk.trustworks.intranet.userservice.model.enums;

/**
 * Maps a {@link CareerLevel} to its TW-bonus career multiplier (§2.1 of the
 * career-level-multipliers spec).
 *
 * <p>Ground truth:
 * <ul>
 *   <li>0× (NOT eligible): ASSOCIATE_PARTNER, PARTNER, DIRECTOR, MANAGING_DIRECTOR,
 *       MANAGING_PARTNER, SENIOR_MANAGER</li>
 *   <li>3×: ENGAGEMENT_DIRECTOR</li>
 *   <li>2×: MANAGER, SENIOR_ENGAGEMENT_MANAGER</li>
 *   <li>1.5×: ENGAGEMENT_MANAGER</li>
 *   <li>1× (default, eligible): everything else</li>
 *   <li>null / unknown level → 1.0 (NEVER exclude on missing data)</li>
 * </ul>
 */
public final class CareerLevelMultiplier {

    private CareerLevelMultiplier() {
    }

    /**
     * Returns the bonus multiplier for the given career level.
     * A {@code null} level resolves to 1.0 — missing data never excludes an employee.
     */
    public static double of(CareerLevel level) {
        if (level == null) {
            return 1.0;
        }
        return switch (level) {
            // 0× — not eligible
            case ASSOCIATE_PARTNER, PARTNER, DIRECTOR, MANAGING_DIRECTOR,
                    MANAGING_PARTNER, SENIOR_MANAGER -> 0.0;
            // 3×
            case ENGAGEMENT_DIRECTOR -> 3.0;
            // 2×
            case MANAGER, SENIOR_ENGAGEMENT_MANAGER -> 2.0;
            // 1.5×
            case ENGAGEMENT_MANAGER -> 1.5;
            // 1× — default, eligible
            default -> 1.0;
        };
    }
}

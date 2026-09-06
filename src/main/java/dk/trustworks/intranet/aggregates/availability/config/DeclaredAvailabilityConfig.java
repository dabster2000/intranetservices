package dk.trustworks.intranet.aggregates.availability.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Cut-over controls for declared availability (JK Team 2.0 WP1, spec §4.1.3).
 *
 * <p>{@link Mode#SHADOW} is deliberately the default: the table is written and shown to the
 * junior, the plan-coverage strip counts who has declared, but
 * {@code UserAvailabilityCalculatorService} keeps {@code allocation / 5} for every user.
 * {@link Mode#LIVE} makes a {@code STUDENT} day on or after {@link #floorDate()} resolve to
 * the declaration (missing row = 0 h, D7). The flip is a config change, not a deploy, and
 * reverts the same way.
 *
 * <p>Same shape as {@code TimesheetRuleEnforcementConfig}; the floor date is kept as the raw
 * string here and parsed once by {@link DeclaredAvailabilityPolicy}, so a malformed value
 * fails loudly at startup rather than silently disabling the floor.
 */
@ConfigMapping(prefix = "feature.declared-availability")
public interface DeclaredAvailabilityConfig {

    @WithDefault("SHADOW")
    Mode mode();

    /** ISO date ({@code yyyy-MM-dd}). Days before it keep today's maths forever. */
    @WithName("floor-date")
    @WithDefault("2026-10-01")
    String floorDate();

    /** Kill switch for the Monday reminder DM. Ships dark — see application.yml. */
    @WithName("reminder-enabled")
    @WithDefault("false")
    boolean reminderEnabled();

    enum Mode {
        SHADOW,
        LIVE
    }
}

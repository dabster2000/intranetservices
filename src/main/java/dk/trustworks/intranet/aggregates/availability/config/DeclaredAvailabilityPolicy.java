package dk.trustworks.intranet.aggregates.availability.config;

import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The one place that answers "is this person declaring on this day?" (spec §4.1.3).
 *
 * <pre>
 *   declaring = type == STUDENT  ∧  day ≥ floorDate  ∧  mode == LIVE
 * </pre>
 *
 * Every consumer — the availability resolver, the WP2 leave guard, the recalculation
 * trigger, the WP7 preset override — asks this bean instead of re-deriving the rule, so the
 * cut-over is exactly one config flip. The static {@link #declaring} is the pure form the
 * unit tests exercise over the full matrix.
 */
@JBossLog
@ApplicationScoped
public class DeclaredAvailabilityPolicy {

    @Inject
    DeclaredAvailabilityConfig config;

    private LocalDate floorDate;

    @PostConstruct
    void init() {
        try {
            floorDate = LocalDate.parse(config.floorDate().trim());
        } catch (DateTimeParseException e) {
            // Fail loudly: a bad floor date must not silently turn into "no floor", which
            // would rewrite every historical junior day the moment LIVE is switched on.
            throw new IllegalStateException(
                    "feature.declared-availability.floor-date is not an ISO date: '" + config.floorDate() + "'", e);
        }
        log.infof("Declared availability: mode=%s floorDate=%s reminder=%s",
                config.mode(), floorDate, config.reminderEnabled());
    }

    public DeclaredAvailabilityConfig.Mode mode() {
        return config.mode();
    }

    public LocalDate floorDate() {
        return floorDate;
    }

    public boolean isLive() {
        return config.mode() == DeclaredAvailabilityConfig.Mode.LIVE;
    }

    public boolean reminderEnabled() {
        return config.reminderEnabled();
    }

    /** Whether the declaration (or its absence) decides this person's availability on {@code day}. */
    public boolean isDeclaring(ConsultantType type, LocalDate day) {
        return declaring(config.mode(), floorDate, type, day);
    }

    /** Pure form of {@link #isDeclaring}. */
    public static boolean declaring(DeclaredAvailabilityConfig.Mode mode, LocalDate floorDate,
                                    ConsultantType type, LocalDate day) {
        if (mode != DeclaredAvailabilityConfig.Mode.LIVE) {
            return false;
        }
        if (type != ConsultantType.STUDENT) {
            return false;
        }
        if (day == null || floorDate == null) {
            return false;
        }
        return !day.isBefore(floorDate);
    }
}

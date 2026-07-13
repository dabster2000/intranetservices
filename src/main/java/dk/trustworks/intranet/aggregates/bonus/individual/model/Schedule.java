package dk.trustworks.intranet.aggregates.bonus.individual.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The payout schedule of an individual bonus rule.
 *
 * @param cadence YEARLY | MONTHLY | MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP
 * @param yearly  yearly-cadence settings (used when {@code cadence == YEARLY})
 * @param advance monthly-advance settings (used for MONTHLY / ADVANCE cadences)
 * @param trueUp  year-end true-up settings (used for ADVANCE_PLUS_YEARLY_TRUEUP)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Schedule(
        Cadence cadence,
        @JsonInclude(JsonInclude.Include.NON_NULL) MonthlySchedule monthly,
        Yearly yearly,
        Advance advance,
        TrueUp trueUp
) {
    /** Backward-compatible constructor for the legacy fiscal-year schedules. */
    public Schedule(Cadence cadence, Yearly yearly, Advance advance, TrueUp trueUp) {
        this(cadence, null, yearly, advance, trueUp);
    }
}

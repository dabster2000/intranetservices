package dk.trustworks.intranet.aggregates.finance.dto;

import dk.trustworks.intranet.aggregates.userprofile.dto.CompetenceTagDTO;
import dk.trustworks.intranet.contracts.dto.ZeroRateWatchlistDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * Junior Talent (JK Team 2.0 WP7, spec §4.7.1): who is free, what can they do, when do they
 * finish — every active STUDENT, read-only, gated on {@code capacity:read}.
 *
 * <p>Deliberately excludes anything salary-adjacent, the WP1 declaration {@code note} and the
 * free-text {@code education} field (§4.7.3): discipline, tags, graduation period and capacity
 * only.
 *
 * @param from         first day of the availability window (a Monday)
 * @param to           last day of the window
 * @param horizonWeeks weeks in the window
 */
public record JuniorTalentDTO(
        LocalDate from,
        LocalDate to,
        int horizonWeeks,
        List<JuniorRow> juniors,
        List<ZeroRateWatchlistDTO> watchlist
) {

    /**
     * @param declaredHours        declared hours in the window; null when no working day is declared
     * @param undeclaredWorkingDays working days in the window with no declaration (D7: "No plan (3 days)")
     * @param freeHours            declared − contract budget − internal budget, never below 0
     * @param freePercent          freeHours ÷ declaredHours × 100 — the denominator is their own declared
     *                             week, never 37 h; null when undeclared or on leave
     * @param onLeave              orlov covers the window — render "På orlov", not a percentage
     */
    public record JuniorRow(
            String userId,
            String firstname,
            String lastname,
            String teamName,
            String studyLevel,
            LocalDate expectedGraduation,
            String primaryDiscipline,
            List<CompetenceTagDTO> tags,
            Double declaredHours,
            int undeclaredWorkingDays,
            double contractBudgetHours,
            double internalBudgetHours,
            double freeHours,
            Double freePercent,
            boolean onLeave,
            /** Active client lines in the window: client names for the "what are they on" glance */
            List<String> currentClients
    ) {}
}

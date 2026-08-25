package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.Map;
import java.util.Objects;

/**
 * One client row inside an industry segment's drill-down panel.
 *
 * @param clientUuid                 client UUID; {@code null} for the synthetic
 *                                   "(Unattributed)" bucket of revenue rows whose
 *                                   client could not be resolved
 * @param clientName                 client display name
 * @param windowRevenueDkk           invoiced net revenue across the actual window
 *                                   (12 full quarters + current quarter to date)
 * @param latestFullQuarterRevenueDkk invoiced net revenue in the latest full quarter
 * @param sharePercent               this client's share of the segment's window revenue (0–100)
 * @param forecastBudgetDkk          contracted budget revenue in the forecast window
 *                                   (current quarter start → forecast end)
 * @param isNew                      first-ever invoice falls inside the 3-year window
 * @param isQuiet                    had window revenue but none in the last two full quarters
 * @param quarterRevenueDkk          actual invoiced revenue per quarter key (only
 *                                   quarters with non-zero revenue are present)
 */
public record IndustryTrendClientDTO(
        String clientUuid,
        String clientName,
        double windowRevenueDkk,
        double latestFullQuarterRevenueDkk,
        double sharePercent,
        double forecastBudgetDkk,
        boolean isNew,
        boolean isQuiet,
        Map<String, Double> quarterRevenueDkk
) {
    public IndustryTrendClientDTO {
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(quarterRevenueDkk, "quarterRevenueDkk");
    }
}

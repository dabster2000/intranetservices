package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One engagement row in an industry's engagement drill-down panel.
 *
 * @param consultantName  consultant display name
 * @param clientUuid      client UUID
 * @param clientName      client display name
 * @param startMonthKey   first active month of the engagement, {@code YYYYMM}
 * @param runningMonths   months from the first through the last active month
 * @param active          {@code true} while the engagement has active work in
 *                        or after the latest full quarter
 */
public record IndustryEngagementItemDTO(
        String consultantName,
        String clientUuid,
        String clientName,
        String startMonthKey,
        int runningMonths,
        boolean active
) {}

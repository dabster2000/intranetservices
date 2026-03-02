package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wrapper DTO for the GET /delivery/cxo/break-even-utilization response.
 * Contains both company-wide aggregate and per-career-level breakdown.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BreakEvenUtilizationDTO {

    /**
     * Company-wide break-even utilization aggregate.
     * Computed as weighted averages across all career levels.
     */
    private BreakEvenCompanyWideDTO companyWide;

    /**
     * Per-career-level break-even utilization breakdown.
     * Each entry corresponds to one career level row in fact_minimum_viable_rate.
     * Ordered by career level name.
     */
    private List<BreakEvenCareerLevelDTO> byCareerLevel;
}

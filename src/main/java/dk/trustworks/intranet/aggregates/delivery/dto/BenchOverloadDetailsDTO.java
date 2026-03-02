package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO combining bench and overloaded consultant name lists.
 * Returned by GET /delivery/cxo/bench-overload-details.
 *
 * The consultant counts in this response are consistent with the sibling endpoints:
 * - bench-count (< 50% utilization, trailing 28-day window)
 * - overload-count (> 95% utilization, trailing 28-day window)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BenchOverloadDetailsDTO {

    /**
     * Consultants currently on bench (< 50% utilization in trailing 28-day window).
     * Ordered by benchWeeks DESC, then name ASC.
     */
    private List<BenchConsultantDTO> benchConsultants;

    /**
     * Consultants currently overloaded (> 95% utilization in trailing 28-day window).
     * Ordered by allocationPercent DESC, then name ASC.
     */
    private List<OverloadConsultantDTO> overloadedConsultants;
}

package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bench FTE count per practice for current and prior month.
 * "Bench" = active consultant whose budget utilization is below 10%.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PracticeBenchFteDTO {

    /** Practice code: PM, BA, CYB, DEV, SA */
    private String practiceId;

    /** Number of consultants on bench this month */
    private int currentBenchFte;

    /** Number of consultants on bench last month */
    private int priorBenchFte;

    /** Delta = current - prior */
    private int benchFteDelta;
}

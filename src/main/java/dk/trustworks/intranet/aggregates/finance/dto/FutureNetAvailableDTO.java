package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Net available hours aggregated by month for future months.
 * Used by the Executive utilization trend chart for budget utilization denominator.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FutureNetAvailableDTO {

    /** Month key in format YYYYMM (e.g., "202607") */
    private String monthKey;

    /** Year component (e.g., 2026) */
    private int year;

    /** Month number component (1-12) */
    private int monthNumber;

    /** Total net available hours for the month */
    private double totalNet;
}

package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Billing success grouped by rate band.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateBandEntry {
    private String band;
    private int jkClientMonths;
    private double billingPercent;
}

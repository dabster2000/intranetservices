package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Monthly profitability breakdown for the JK team across a fiscal year.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkProfitabilityResponse {
    private List<JkProfitabilityMonth> months;
}

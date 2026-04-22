package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Output of the DeltaAbsorptionEngine. Per-line attributions keyed by line UUID.
 * Each entry maps consultantUuid -> attributed hours. The sum of hours for
 * a given line equals the line's current hours.
 */
public record DeltaAbsorptionResult(
    Map<String, List<ConsultantShare>> attributionsByLine,
    BigDecimal totalDeletedPool,
    BigDecimal totalPositiveDelta,
    BigDecimal unattributedPoolHours
) {
    public record ConsultantShare(String consultantUuid, BigDecimal hours) {}
}

package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.math.BigDecimal;

public record LineDelta(
    String lineUuid,
    String consultantUuid,
    BigDecimal currentHours,
    BigDecimal baselineHours,
    Classification classification
) {
    public BigDecimal delta() {
        return currentHours.subtract(baselineHours);
    }
    public boolean isIncreased() {
        return classification == Classification.INCREASED;
    }
}

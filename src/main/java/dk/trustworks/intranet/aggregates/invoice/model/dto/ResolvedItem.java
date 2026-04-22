package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.math.BigDecimal;
import java.util.List;

public record ResolvedItem(
    String itemUuid,
    String description,
    BigDecimal hours,
    BigDecimal amount,
    List<ResolvedAttribution> attributions,
    String confidence,
    String reasoning,
    BigDecimal baselineHours,
    BigDecimal delta
) {
    public ResolvedItem(
        String itemUuid, String description, BigDecimal hours, BigDecimal amount,
        List<ResolvedAttribution> attributions, String confidence, String reasoning
    ) {
        this(itemUuid, description, hours, amount, attributions, confidence, reasoning, null, null);
    }
}

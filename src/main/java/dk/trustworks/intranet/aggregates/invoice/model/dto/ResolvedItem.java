package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.math.BigDecimal;
import java.util.List;

public record ResolvedItem(
    String itemUuid,
    String description,
    BigDecimal hours,
    BigDecimal amount,
    List<ResolvedAttribution> attributions,
    String confidence,  // HIGH, MEDIUM, LOW
    String reasoning
) {}

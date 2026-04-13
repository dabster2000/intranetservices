package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.math.BigDecimal;

public record ResolvedAttribution(
    String consultantUuid,
    String consultantName,
    BigDecimal sharePct,
    BigDecimal attributedAmount,
    BigDecimal attributedHours
) {}

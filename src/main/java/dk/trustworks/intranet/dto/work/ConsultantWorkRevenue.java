package dk.trustworks.intranet.dto.work;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;

/**
 * Per-consultant aggregate of registered work for a client in a month:
 * {@code hours} = Σ workduration, {@code revenue} = Σ (workduration × rate).
 * Produced by {@code WorkService.findRevenueByClientAndMonth}.
 */
@RegisterForReflection
public record ConsultantWorkRevenue(
        String useruuid,
        BigDecimal hours,
        BigDecimal revenue
) {}

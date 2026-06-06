package dk.trustworks.intranet.aggregates.invoice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Per-group Settle preview: one ConsultantDelta per cross-company consultant,
 * grouped by issuer company, with group-level rollups. delta = target - settled.
 */
public record SettlementGroupPreview(
        SettlementGroupKey key,
        String debtorCompanyUuid,
        String debtorCompanyName,
        List<IssuerDelta> issuers,
        BigDecimal totalTarget,
        BigDecimal totalSettled,
        BigDecimal totalDelta,
        boolean allResolved
) {
    public record IssuerDelta(
            String issuerCompanyUuid,
            String issuerCompanyName,
            List<ConsultantDelta> consultants,
            BigDecimal target,
            BigDecimal settled,
            BigDecimal delta
    ) {}

    public record ConsultantDelta(
            String consultantUuid,
            String consultantName,
            BigDecimal target,
            BigDecimal settled,
            BigDecimal delta
    ) {}
}

package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;

import java.util.List;

public record PartnerDashboardDTO(
        int fiscalYear,
        String fiscalYearStart,
        String fiscalYearEnd,
        List<BonusEligibilityGroup> groups,
        KpiSummaryDTO summary,
        List<ConsultantBonusDTO> consultants,
        List<GroupAnalyticsDTO> groupAnalytics,
        String dataTimestamp,
        boolean cached
) {}

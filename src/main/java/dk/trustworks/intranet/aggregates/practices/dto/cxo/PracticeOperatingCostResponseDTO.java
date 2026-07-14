package dk.trustworks.intranet.aggregates.practices.dto.cxo;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

/** Group-only operating-cost response. The nullable freshness timestamp is the aggregate evidence refresh time in UTC. */
public record PracticeOperatingCostResponseDTO(
        String costSource,
        String reportingThroughMonthKey,
        String currentPeriodStartMonthKey,
        String currentPeriodEndMonthKey,
        String priorPeriodStartMonthKey,
        String priorPeriodEndMonthKey,
        Instant sourceRefreshedAt,
        int currentSalaryMonthCount,
        int currentOpexMonthCount,
        int currentFteMonthCount,
        int priorSalaryMonthCount,
        int priorOpexMonthCount,
        int priorFteMonthCount,
        int currentExpectedSalaryCellCount,
        int currentActualSalaryCellCount,
        int currentCoveredSalaryCellCount,
        int currentMissingSalaryCellCount,
        int currentUnexpectedSalaryCellCount,
        int priorExpectedSalaryCellCount,
        int priorActualSalaryCellCount,
        int priorCoveredSalaryCellCount,
        int priorMissingSalaryCellCount,
        int priorUnexpectedSalaryCellCount,
        int currentExpectedFteCellCount,
        int currentCoveredFteCellCount,
        int currentMissingFteCellCount,
        int priorExpectedFteCellCount,
        int priorCoveredFteCellCount,
        int priorMissingFteCellCount,
        boolean currentCostComplete,
        boolean currentFteComplete,
        String currentCompletenessStatus,
        boolean priorCostComplete,
        boolean priorFteComplete,
        String priorCompletenessStatus,
        String completenessStatus,
        boolean complete,
        String practiceAttribution,
        LocalDate practiceAttributionCoverageStartDate,
        String attributionNote,
        List<PracticeOperatingCostDTO> practices
) {
    public PracticeOperatingCostResponseDTO {
        practices = List.copyOf(practices);
    }
}

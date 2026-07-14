package dk.trustworks.intranet.aggregates.practices.dto.cxo;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

/** Group-only operating-cost response. The freshness timestamp is a nullable best-effort table-write hint. */
public record PracticeOperatingCostResponseDTO(
        String costSource,
        String reportingThroughMonthKey,
        String currentPeriodStartMonthKey,
        String currentPeriodEndMonthKey,
        String priorPeriodStartMonthKey,
        String priorPeriodEndMonthKey,
        LocalDateTime sourceRefreshedAt,
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

package dk.trustworks.intranet.aggregates.practices.dto.cxo;

/** Fully loaded group operating cost for one billable practice over current and prior TTM periods. */
public record PracticeOperatingCostDTO(
        String practiceId,
        Double currentSalaryDkk,
        Double currentOpexDkk,
        Double currentTotalDkk,
        Double priorSalaryDkk,
        Double priorOpexDkk,
        Double priorTotalDkk,
        Double totalDeltaDkk,
        Double totalDeltaPct,
        Double currentAverageFte,
        Double priorAverageFte,
        Double currentCostPerFteDkk,
        Double priorCostPerFteDkk,
        Double costPerFteDeltaDkk,
        Double costPerFteDeltaPct
) {}

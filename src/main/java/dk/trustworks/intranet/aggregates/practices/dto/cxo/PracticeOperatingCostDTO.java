package dk.trustworks.intranet.aggregates.practices.dto.cxo;

/** Fully loaded group operating cost for one billable practice over current and prior TTM periods. */
public record PracticeOperatingCostDTO(
        String practiceId,
        double currentSalaryDkk,
        double currentOpexDkk,
        double currentTotalDkk,
        double priorSalaryDkk,
        double priorOpexDkk,
        double priorTotalDkk,
        double totalDeltaDkk,
        Double totalDeltaPct,
        double currentAverageFte,
        double priorAverageFte,
        Double currentCostPerFteDkk,
        Double priorCostPerFteDkk,
        Double costPerFteDeltaDkk,
        Double costPerFteDeltaPct
) {}

package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.Classification;
import dk.trustworks.intranet.aggregates.invoice.model.dto.DeltaAbsorptionResult;
import dk.trustworks.intranet.aggregates.invoice.model.dto.LineDelta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure (no Quarkus, no DB) delta-based absorption algorithm.
 * See docs/superpowers/specs/2026-04-22-invoice-attribution-delta-absorption-design.md
 */
public final class DeltaAbsorptionEngine {

    public record CurrentLine(String lineUuid, String consultantUuid, BigDecimal currentHours) {}

    private DeltaAbsorptionEngine() {}

    public static List<LineDelta> classifyLines(
        Map<String, BigDecimal> baselineHoursByConsultant,
        List<CurrentLine> currentLines
    ) {
        List<LineDelta> result = new ArrayList<>();
        Set<String> currentConsultants = new HashSet<>();

        Map<String, BigDecimal> currentHoursByConsultant = new HashMap<>();
        for (CurrentLine cl : currentLines) {
            currentConsultants.add(cl.consultantUuid());
            currentHoursByConsultant.merge(cl.consultantUuid(), cl.currentHours(), BigDecimal::add);
        }

        for (CurrentLine cl : currentLines) {
            BigDecimal baseline = baselineHoursByConsultant.getOrDefault(cl.consultantUuid(), BigDecimal.ZERO);
            BigDecimal totalCurrentForConsultant = currentHoursByConsultant.get(cl.consultantUuid());

            Classification cls;
            BigDecimal perLineBaseline;
            if (baseline.signum() == 0) {
                cls = Classification.ADDED;
                perLineBaseline = BigDecimal.ZERO;
            } else {
                perLineBaseline = baseline.multiply(cl.currentHours())
                    .divide(totalCurrentForConsultant, 4, RoundingMode.HALF_UP);
                int cmp = cl.currentHours().compareTo(perLineBaseline);
                cls = cmp == 0 ? Classification.UNTOUCHED
                     : cmp > 0 ? Classification.INCREASED
                               : Classification.DECREASED;
            }
            result.add(new LineDelta(cl.lineUuid(), cl.consultantUuid(), cl.currentHours(),
                                     perLineBaseline, cls));
        }

        for (var entry : baselineHoursByConsultant.entrySet()) {
            if (!currentConsultants.contains(entry.getKey())) {
                result.add(new LineDelta(null, entry.getKey(), BigDecimal.ZERO,
                                         entry.getValue(), Classification.DELETED));
            }
        }
        return result;
    }

    public static DeltaAbsorptionResult resolve(
        Map<String, BigDecimal> baselineHoursByConsultant,
        List<CurrentLine> currentLines
    ) {
        List<LineDelta> classified = classifyLines(baselineHoursByConsultant, currentLines);

        // Deleted pool
        List<LineDelta> deleted = classified.stream()
            .filter(ld -> ld.classification() == Classification.DELETED).toList();
        BigDecimal totalPool = deleted.stream().map(LineDelta::baselineHours)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Internal ratio per deleted consultant (share of the pool)
        Map<String, BigDecimal> internalRatio = new LinkedHashMap<>();
        if (totalPool.signum() > 0) {
            for (LineDelta d : deleted) {
                internalRatio.put(d.consultantUuid(),
                    d.baselineHours().divide(totalPool, 6, RoundingMode.HALF_UP));
            }
        }

        // Total positive delta across increased lines
        List<LineDelta> increased = classified.stream()
            .filter(ld -> ld.classification() == Classification.INCREASED).toList();
        BigDecimal totalDelta = increased.stream().map(LineDelta::delta)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Per-line attribution
        Map<String, List<DeltaAbsorptionResult.ConsultantShare>> byLine = new LinkedHashMap<>();
        BigDecimal totalAbsorbed = BigDecimal.ZERO;

        for (CurrentLine cl : currentLines) {
            LineDelta ld = classified.stream()
                .filter(x -> cl.lineUuid().equals(x.lineUuid()))
                .findFirst().orElseThrow();
            List<DeltaAbsorptionResult.ConsultantShare> shares = new ArrayList<>();

            BigDecimal absorbed = BigDecimal.ZERO;
            if (ld.isIncreased() && totalPool.signum() > 0 && totalDelta.signum() > 0) {
                BigDecimal ratio = totalPool.divide(totalDelta, 6, RoundingMode.HALF_UP);
                if (ratio.compareTo(BigDecimal.ONE) > 0) ratio = BigDecimal.ONE;
                absorbed = ld.delta().multiply(ratio).setScale(4, RoundingMode.HALF_UP);

                BigDecimal runningSum = BigDecimal.ZERO;
                int count = 0;
                for (var e : internalRatio.entrySet()) {
                    count++;
                    BigDecimal h;
                    if (count == internalRatio.size()) {
                        // Last deleted consultant gets remainder to avoid rounding drift
                        h = absorbed.subtract(runningSum);
                    } else {
                        h = absorbed.multiply(e.getValue()).setScale(4, RoundingMode.HALF_UP);
                        runningSum = runningSum.add(h);
                    }
                    if (h.signum() > 0) {
                        shares.add(new DeltaAbsorptionResult.ConsultantShare(e.getKey(), h));
                    }
                }
                totalAbsorbed = totalAbsorbed.add(absorbed);
            }

            BigDecimal lineConsultantHours = cl.currentHours().subtract(absorbed);
            if (lineConsultantHours.signum() > 0) {
                shares.add(0, new DeltaAbsorptionResult.ConsultantShare(cl.consultantUuid(), lineConsultantHours));
            }
            byLine.put(cl.lineUuid(), shares);
        }

        BigDecimal unattributed = totalPool.subtract(totalAbsorbed).max(BigDecimal.ZERO);
        return new DeltaAbsorptionResult(byLine, totalPool, totalDelta, unattributed);
    }
}

package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.Classification;
import dk.trustworks.intranet.aggregates.invoice.model.dto.LineDelta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
}

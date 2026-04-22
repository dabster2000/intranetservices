package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.Classification;
import dk.trustworks.intranet.aggregates.invoice.model.dto.LineDelta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeltaAbsorptionEngineTest {

    @Test
    void classifyLines_untouchedIncreasedDeleted() {
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("23.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("67.0"))
        );

        List<LineDelta> result = DeltaAbsorptionEngine.classifyLines(baseline, current);

        assertEquals(3, result.size(), "Expected 3 LineDeltas (2 current lines + 1 deleted)");

        LineDelta henrik = result.stream()
            .filter(ld -> "henrik".equals(ld.consultantUuid()))
            .findFirst().orElseThrow();
        assertEquals(Classification.UNTOUCHED, henrik.classification());
        assertEquals(0, henrik.delta().compareTo(BigDecimal.ZERO));

        LineDelta mathias = result.stream()
            .filter(ld -> "mathias".equals(ld.consultantUuid()))
            .findFirst().orElseThrow();
        assertEquals(Classification.INCREASED, mathias.classification());
        assertEquals(0, mathias.delta().compareTo(new BigDecimal("8.0")));

        LineDelta elvi = result.stream()
            .filter(ld -> "elvi".equals(ld.consultantUuid()))
            .findFirst().orElseThrow();
        assertEquals(Classification.DELETED, elvi.classification());
        assertEquals(0, elvi.baselineHours().compareTo(new BigDecimal("8.0")));
        assertEquals(0, elvi.currentHours().compareTo(BigDecimal.ZERO));
    }
}

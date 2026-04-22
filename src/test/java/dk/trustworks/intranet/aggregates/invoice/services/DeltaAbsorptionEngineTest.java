package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.Classification;
import dk.trustworks.intranet.aggregates.invoice.model.dto.DeltaAbsorptionResult;
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

    @Test
    void resolve_case1_singleAbsorberDeltaEqualsPool() {
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("23.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("67.0"))
        );

        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        List<DeltaAbsorptionResult.ConsultantShare> henrikShares = r.attributionsByLine().get("L1");
        assertEquals(1, henrikShares.size(), "Henrik line should have one attribution");
        assertEquals("henrik", henrikShares.get(0).consultantUuid());
        assertEquals(0, henrikShares.get(0).hours().compareTo(new BigDecimal("23.5")));

        List<DeltaAbsorptionResult.ConsultantShare> mathiasShares = r.attributionsByLine().get("L3");
        BigDecimal mathiasHours = mathiasShares.stream()
            .filter(s -> s.consultantUuid().equals("mathias"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        BigDecimal elviHours = mathiasShares.stream()
            .filter(s -> s.consultantUuid().equals("elvi"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        assertEquals(0, mathiasHours.compareTo(new BigDecimal("59.0")));
        assertEquals(0, elviHours.compareTo(new BigDecimal("8.0")));

        assertEquals(0, r.unattributedPoolHours().compareTo(BigDecimal.ZERO));
    }

    @Test
    void resolve_case2_singleAbsorberDeltaExceedsPool() {
        // Baseline: Henrik 23.5, Elvi 8, Mathias 59
        // User deletes Elvi, bumps Mathias 59 -> 70 (delta +11, pool only 8)
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("23.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("70.0"))
        );

        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        List<DeltaAbsorptionResult.ConsultantShare> mathiasShares = r.attributionsByLine().get("L3");
        BigDecimal mathiasHours = mathiasShares.stream()
            .filter(s -> s.consultantUuid().equals("mathias"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        BigDecimal elviHours = mathiasShares.stream()
            .filter(s -> s.consultantUuid().equals("elvi"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        assertEquals(0, mathiasHours.compareTo(new BigDecimal("62.0")),
            "Mathias = 59 baseline + 3 surplus delta = 62");
        assertEquals(0, elviHours.compareTo(new BigDecimal("8.0")),
            "Elvi absorbed at her full 8h pool");
        assertEquals(0, r.unattributedPoolHours().compareTo(BigDecimal.ZERO));
    }

    @Test
    void resolve_case3_twoAbsorbersProportional() {
        // Baseline: Henrik 23.5, Elvi 8, Mathias 59
        // User deletes Elvi, bumps Henrik 23.5 -> 25.5 (+2) and Mathias 59 -> 65 (+6)
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("25.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("65.0"))
        );

        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        List<DeltaAbsorptionResult.ConsultantShare> henrikShares = r.attributionsByLine().get("L1");
        BigDecimal henrikHenrik = henrikShares.stream()
            .filter(s -> s.consultantUuid().equals("henrik"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        BigDecimal henrikElvi = henrikShares.stream()
            .filter(s -> s.consultantUuid().equals("elvi"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        assertEquals(0, henrikHenrik.compareTo(new BigDecimal("23.5")));
        assertEquals(0, henrikElvi.compareTo(new BigDecimal("2.0")));

        List<DeltaAbsorptionResult.ConsultantShare> mathiasShares = r.attributionsByLine().get("L3");
        BigDecimal mathiasMathias = mathiasShares.stream()
            .filter(s -> s.consultantUuid().equals("mathias"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        BigDecimal mathiasElvi = mathiasShares.stream()
            .filter(s -> s.consultantUuid().equals("elvi"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        assertEquals(0, mathiasMathias.compareTo(new BigDecimal("59.0")));
        assertEquals(0, mathiasElvi.compareTo(new BigDecimal("6.0")));
    }

    @Test
    void resolve_case4_multipleDeletedPoolExceedsDelta() {
        // Baseline: Mathias 31, Jeppe 84, Casper 56.5, Simone 54
        // User deletes Casper + Simone, bumps Mathias +20, Jeppe +20
        Map<String, BigDecimal> baseline = Map.of(
            "mathias", new BigDecimal("31.0"),
            "jeppe",   new BigDecimal("84.0"),
            "casper",  new BigDecimal("56.5"),
            "simone",  new BigDecimal("54.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("M", "mathias", new BigDecimal("51.0")),
            new DeltaAbsorptionEngine.CurrentLine("J", "jeppe",   new BigDecimal("104.0"))
        );

        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        // Each line absorbs 20h capped by delta
        List<DeltaAbsorptionResult.ConsultantShare> m = r.attributionsByLine().get("M");
        BigDecimal mMathias = m.stream().filter(s -> s.consultantUuid().equals("mathias"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        BigDecimal mCasper = m.stream().filter(s -> s.consultantUuid().equals("casper"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        BigDecimal mSimone = m.stream().filter(s -> s.consultantUuid().equals("simone"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        assertEquals(0, mMathias.compareTo(new BigDecimal("31.0")));
        assertEquals(0, mCasper.add(mSimone).compareTo(new BigDecimal("20.0")),
            "Casper + Simone per line = 20h");
        assertTrue(mCasper.compareTo(mSimone) > 0, "Casper > Simone (larger pool share)");

        List<DeltaAbsorptionResult.ConsultantShare> j = r.attributionsByLine().get("J");
        BigDecimal jJeppe = j.stream().filter(s -> s.consultantUuid().equals("jeppe"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours).findFirst().orElseThrow();
        assertEquals(0, jJeppe.compareTo(new BigDecimal("84.0")));

        // Total unattributed: 110.5 - 40 = 70.5
        assertEquals(0, r.unattributedPoolHours().compareTo(new BigDecimal("70.5")));

        // Eligibility: no one outside the baseline set
        for (var entry : r.attributionsByLine().entrySet()) {
            for (DeltaAbsorptionResult.ConsultantShare share : entry.getValue()) {
                assertTrue(List.of("mathias", "jeppe", "casper", "simone")
                    .contains(share.consultantUuid()),
                    "Unexpected consultant in output: " + share.consultantUuid());
            }
        }
    }

    @Test
    void decreasedLineDoesNotAbsorb() {
        // Baseline: Henrik 23.5, Elvi 8, Mathias 59
        // User deletes Elvi, decreases Mathias 59 -> 50 (no positive delta)
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("23.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("50.0"))
        );
        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        List<DeltaAbsorptionResult.ConsultantShare> mShares = r.attributionsByLine().get("L3");
        assertEquals(1, mShares.size(), "Decreased line: 100% own consultant");
        assertEquals("mathias", mShares.get(0).consultantUuid());
        assertEquals(0, mShares.get(0).hours().compareTo(new BigDecimal("50.0")));
        assertEquals(0, r.unattributedPoolHours().compareTo(new BigDecimal("8.0")));
    }

    @Test
    void newlyAddedLineDoesNotAbsorb() {
        // Baseline: Henrik 23.5, Elvi 8, Mathias 59
        // User deletes Elvi, adds NEW Sarah 8h line (Sarah not in baseline)
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("23.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("59.0")),
            new DeltaAbsorptionEngine.CurrentLine("LS", "sarah",   new BigDecimal("8.0"))
        );
        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        List<DeltaAbsorptionResult.ConsultantShare> sarah = r.attributionsByLine().get("LS");
        assertEquals(1, sarah.size());
        assertEquals("sarah", sarah.get(0).consultantUuid());
        assertEquals(0, sarah.get(0).hours().compareTo(new BigDecimal("8.0")));
        assertEquals(0, r.unattributedPoolHours().compareTo(new BigDecimal("8.0")));
    }

    @Test
    void allLinesUntouchedNoAbsorption() {
        // Baseline: Henrik 23.5, Elvi 8, Mathias 59
        // User deletes Elvi but makes no other changes -> no positive delta lines
        Map<String, BigDecimal> baseline = Map.of(
            "henrik",  new BigDecimal("23.5"),
            "elvi",    new BigDecimal("8.0"),
            "mathias", new BigDecimal("59.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "henrik",  new BigDecimal("23.5")),
            new DeltaAbsorptionEngine.CurrentLine("L3", "mathias", new BigDecimal("59.0"))
        );
        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        assertEquals(1, r.attributionsByLine().get("L1").size());
        assertEquals("henrik", r.attributionsByLine().get("L1").get(0).consultantUuid());
        assertEquals(1, r.attributionsByLine().get("L3").size());
        assertEquals("mathias", r.attributionsByLine().get("L3").get(0).consultantUuid());
        assertEquals(0, r.unattributedPoolHours().compareTo(new BigDecimal("8.0")));
    }

    @Test
    void multiLinePerConsultantTotalElviAttributionCorrect() {
        // Baseline: Mathias 59h (sum of two logical lines), Elvi 8h
        // User deletes Elvi. Two current Mathias lines: A=40, B=29 (total 69 -> +10 vs 59)
        Map<String, BigDecimal> baseline = Map.of(
            "mathias", new BigDecimal("59.0"),
            "elvi",    new BigDecimal("8.0")
        );
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("A", "mathias", new BigDecimal("40.0")),
            new DeltaAbsorptionEngine.CurrentLine("B", "mathias", new BigDecimal("29.0"))
        );
        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);

        BigDecimal totalElviAttributed = r.attributionsByLine().values().stream()
            .flatMap(List::stream)
            .filter(s -> s.consultantUuid().equals("elvi"))
            .map(DeltaAbsorptionResult.ConsultantShare::hours)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, totalElviAttributed.compareTo(new BigDecimal("8.0")),
            "All of Elvi's 8h should be attributed across Mathias's lines");
    }

    @Test
    void emptyBaselineReturnsEmptyResult() {
        Map<String, BigDecimal> baseline = Map.of();
        List<DeltaAbsorptionEngine.CurrentLine> current = List.of(
            new DeltaAbsorptionEngine.CurrentLine("L1", "alice", new BigDecimal("10.0"))
        );
        DeltaAbsorptionResult r = DeltaAbsorptionEngine.resolve(baseline, current);
        List<DeltaAbsorptionResult.ConsultantShare> alice = r.attributionsByLine().get("L1");
        assertEquals(1, alice.size());
        assertEquals("alice", alice.get(0).consultantUuid());
        assertEquals(0, alice.get(0).hours().compareTo(new BigDecimal("10.0")));
        assertEquals(0, r.totalDeletedPool().compareTo(BigDecimal.ZERO));
    }
}

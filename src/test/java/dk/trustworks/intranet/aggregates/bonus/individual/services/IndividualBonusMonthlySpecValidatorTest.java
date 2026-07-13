package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlySchedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyVehicle;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.StepBand;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndividualBonusMonthlySpecValidatorTest {

    @Test
    void exactAuthoritativeBandsValidateAndSelectEveryBoundary() {
        Spec spec = monthlySpec(authoritativeBands());
        assertDoesNotThrow(() -> IndividualBonusMonthlySpecValidator.validate(spec));

        assertAmount("0", "0.000000", spec);
        assertAmount("0", "0.749999", spec);
        assertAmount("2500", "0.750000", spec);
        assertAmount("2500", "0.799999", spec);
        assertAmount("5000", "0.800000", spec);
        assertAmount("7500", "0.850000", spec);
        assertAmount("10000", "0.900000", spec);
        assertAmount("12500", "0.950000", spec);
        assertAmount("12500", "0.999999", spec);
        assertAmount("15000", "1.000000", spec);
        assertAmount("15000", "1.200000", spec);
    }

    @Test
    void negativeRawValueClampsOnlyForSelection() {
        BigDecimal raw = new BigDecimal("-0.010000");
        StepBand selected = IndividualBonusMonthlySpecValidator.selectStep(authoritativeBands(), raw);
        assertEquals(new BigDecimal("-0.010000"), raw);
        assertEquals(0, selected.amount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void selectorRoundsToResolverScaleBeforeSelecting() {
        StepBand selected = IndividualBonusMonthlySpecValidator.selectStep(
                authoritativeBands(), new BigDecimal("0.7499999"));
        assertEquals(0, selected.amount().compareTo(new BigDecimal("2500")));
    }

    @Test
    void closedGrammarRejectsNearMatches() {
        Spec valid = monthlySpec(authoritativeBands());
        assertCode("MONTHLY_BASIS_REQUIRED", new Spec(Basis.BILLABLE_HOURS, valid.aggregation(), null,
                valid.stepTable(), null, null, valid.expectedBaseSalary(), false, null, valid.schedule(), null));
        assertCode("MONTHLY_TIER_TABLE_FORBIDDEN", new Spec(valid.basis(), valid.aggregation(),
                List.of(new Tier(BigDecimal.ZERO, null, BigDecimal.ZERO)), valid.stepTable(),
                null, null, valid.expectedBaseSalary(), false, null, valid.schedule(), null));
        assertCode("MONTHLY_FORMULA_FORBIDDEN", new Spec(valid.basis(), valid.aggregation(), null,
                valid.stepTable(), null, null, valid.expectedBaseSalary(), false, null, valid.schedule(), "0"));
        assertCode("MONTHLY_REPLACEMENT_FORBIDDEN", new Spec(valid.basis(), valid.aggregation(), null,
                valid.stepTable(), null, null, valid.expectedBaseSalary(), false, "YPOT", valid.schedule(), null));
        assertCode("PAY_MONTH_OFFSET_INVALID", new Spec(valid.basis(), valid.aggregation(), null,
                valid.stepTable(), null, null, valid.expectedBaseSalary(), false, null,
                new Schedule(Cadence.MONTHLY, new MonthlySchedule(MonthlyVehicle.MONTHLY_LUMP_SUM, 0),
                        null, null, null), null));
    }

    @Test
    void invalidStepShapesReturnStableCodeAndField() {
        List<StepBand> gap = new ArrayList<>(authoritativeBands());
        gap.set(1, new StepBand(bd("0.76"), bd("0.80"), bd("2500")));
        assertCode("STEP_TABLE_GAP", monthlySpec(gap));

        List<StepBand> overlap = new ArrayList<>(authoritativeBands());
        overlap.set(1, new StepBand(bd("0.74"), bd("0.80"), bd("2500")));
        assertCode("STEP_TABLE_OVERLAP", monthlySpec(overlap));

        List<StepBand> earlyOpen = new ArrayList<>(authoritativeBands());
        earlyOpen.set(0, new StepBand(BigDecimal.ZERO, null, BigDecimal.ZERO));
        assertCode("STEP_THRESHOLD_REQUIRED", monthlySpec(earlyOpen));

        List<StepBand> finiteFinal = new ArrayList<>(authoritativeBands());
        finiteFinal.set(6, new StepBand(BigDecimal.ONE, bd("2"), bd("15000")));
        assertCode("STEP_TABLE_FINAL_OPEN", monthlySpec(finiteFinal));

        List<StepBand> scale = new ArrayList<>(authoritativeBands());
        scale.set(0, new StepBand(new BigDecimal("0.0000000"), bd("0.75"), BigDecimal.ZERO));
        assertCode("STEP_THRESHOLD_PRECISION", monthlySpec(scale));

        List<StepBand> moneyScale = new ArrayList<>(authoritativeBands());
        moneyScale.set(1, new StepBand(bd("0.75"), bd("0.80"), new BigDecimal("2500.001")));
        assertCode("STEP_AMOUNT_INVALID", monthlySpec(moneyScale));
    }

    static Spec monthlySpec(List<StepBand> bands) {
        return new Spec(Basis.UTILIZATION, "CALENDAR_MONTH", null, bands,
                null, null, bd("58000"), false, null,
                new Schedule(Cadence.MONTHLY,
                        new MonthlySchedule(MonthlyVehicle.MONTHLY_LUMP_SUM, 1),
                        null, null, null), null);
    }

    static List<StepBand> authoritativeBands() {
        return List.of(
                new StepBand(bd("0.00"), bd("0.75"), bd("0")),
                new StepBand(bd("0.75"), bd("0.80"), bd("2500")),
                new StepBand(bd("0.80"), bd("0.85"), bd("5000")),
                new StepBand(bd("0.85"), bd("0.90"), bd("7500")),
                new StepBand(bd("0.90"), bd("0.95"), bd("10000")),
                new StepBand(bd("0.95"), bd("1.00"), bd("12500")),
                new StepBand(bd("1.00"), null, bd("15000")));
    }

    private static void assertAmount(String expected, String utilization, Spec spec) {
        StepBand selected = IndividualBonusMonthlySpecValidator.selectStep(
                spec.stepTable(), bd(utilization));
        assertEquals(0, selected.amount().compareTo(bd(expected)));
    }

    private static void assertCode(String expected, Spec spec) {
        IndividualBonusException failure = assertThrows(IndividualBonusException.class,
                () -> IndividualBonusMonthlySpecValidator.validate(spec));
        assertEquals(expected, failure.code());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

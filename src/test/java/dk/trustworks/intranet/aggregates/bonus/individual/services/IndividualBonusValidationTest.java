package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaEngine;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.model.*;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IndividualBonusValidationTest {

    private IndividualBonusService service;

    @BeforeEach
    void setUp() {
        service = new IndividualBonusService();
        service.formulaEngine = mock(BonusFormulaEngine.class);
    }

    @Test
    void canonicalYearlySpecPasses() {
        assertDoesNotThrow(() -> service.validateSpec(yearlySpec()));
    }

    @Test
    void aggregationMustBeExact() {
        Spec bad = copy(yearlySpec(), "CALENDAR_YEAR_SUM", yearlySpec().schedule(),
                yearlySpec().proRating(), yearlySpec().tierTable(), null, "YPOT");
        assertMessageContains("FISCAL_YEAR_SUM", () -> service.validateSpec(bad));
    }

    @Test
    void cadenceRequiresOnlyItsSupportedBranches() {
        Spec missingYearly = copy(yearlySpec(), "FISCAL_YEAR_SUM",
                new Schedule(Cadence.YEARLY, null, null, null),
                yearlySpec().proRating(), yearlySpec().tierTable(), null, "YPOT");
        assertMessageContains("schedule.yearly is required", () -> service.validateSpec(missingYearly));

        Spec yearlyWithIgnoredAdvance = copy(yearlySpec(), "FISCAL_YEAR_SUM",
                new Schedule(Cadence.YEARLY, new Yearly(1), fixedAdvance(), null),
                yearlySpec().proRating(), yearlySpec().tierTable(), null, "YPOT");
        assertThrows(BadRequestException.class, () -> service.validateSpec(yearlyWithIgnoredAdvance));

        Spec monthlyMissingAdvance = copy(yearlySpec(), "FISCAL_YEAR_SUM",
                new Schedule(Cadence.MONTHLY, null, null, null),
                yearlySpec().proRating(), yearlySpec().tierTable(), null, "YPOT");
        assertMessageContains("schedule.advance is required", () -> service.validateSpec(monthlyMissingAdvance));

        Spec trueUpMissing = copy(yearlySpec(), "FISCAL_YEAR_SUM",
                new Schedule(Cadence.MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP, null, fixedAdvance(), null),
                yearlySpec().proRating(), yearlySpec().tierTable(), null, "YPOT");
        assertMessageContains("schedule.trueUp is required", () -> service.validateSpec(trueUpMissing));
    }

    @Test
    void fixedAmountMustBeMonthlyFixedWithNoTiersOrAutomaticProrating() {
        Spec valid = new Spec(Basis.FIXED_AMOUNT, "FISCAL_YEAR_SUM", null,
                new ProRating(false), null, false, null,
                new Schedule(Cadence.MONTHLY, null, fixedAdvance(), null), null);
        assertDoesNotThrow(() -> service.validateSpec(valid));

        Spec yearly = copy(valid, "FISCAL_YEAR_SUM",
                new Schedule(Cadence.YEARLY, new Yearly(1), null, null),
                valid.proRating(), null, null, null);
        assertMessageContains("FIXED_AMOUNT requires MONTHLY", () -> service.validateSpec(yearly));

        Spec tiered = copy(valid, "FISCAL_YEAR_SUM", valid.schedule(), valid.proRating(), tiers(), null, null);
        assertMessageContains("tierTable", () -> service.validateSpec(tiered));

        Spec prorated = copy(valid, "FISCAL_YEAR_SUM", valid.schedule(), new ProRating(true), null, null, null);
        assertMessageContains("cannot be automatically pro-rated", () -> service.validateSpec(prorated));
    }

    @Test
    void advanceTypeSelectsExactlyOneValue_andUsesSupportedMonthsToken() {
        Advance fixedWithPercent = new Advance(Vehicle.MONTHLY_LUMP_SUM, AdvanceType.FIXED,
                bd("5000"), bd("0.5"), "EMPLOYED_IN_FY");
        assertMessageContains("percentOfProjected must be null",
                () -> service.validateSpec(monthlySpec(fixedWithPercent)));

        Advance percentWithAmount = new Advance(Vehicle.MONTHLY_LUMP_SUM, AdvanceType.PERCENT_OF_PROJECTED,
                bd("5000"), bd("0.5"), "EMPLOYED_IN_FY");
        assertMessageContains("fixedAmountPerMonth must be null",
                () -> service.validateSpec(monthlySpec(percentWithAmount)));

        Advance missingSelected = new Advance(Vehicle.MONTHLY_LUMP_SUM, AdvanceType.PERCENT_OF_PROJECTED,
                null, null, "EMPLOYED_IN_FY");
        assertMessageContains("percentOfProjected is required",
                () -> service.validateSpec(monthlySpec(missingSelected)));

        Advance wrongMonths = new Advance(Vehicle.MONTHLY_LUMP_SUM, AdvanceType.FIXED,
                bd("5000"), null, "ALL_MONTHS");
        assertMessageContains("EMPLOYED_IN_FY", () -> service.validateSpec(monthlySpec(wrongMonths)));
    }

    @Test
    void prepaidSupplementRequiresFixedAdvance() {
        Advance percent = new Advance(Vehicle.PREPAID_SUPPLEMENT, AdvanceType.PERCENT_OF_PROJECTED,
                null, bd("0.5"), "EMPLOYED_IN_FY");
        assertMessageContains("PREPAID_SUPPLEMENT", () -> service.validateSpec(monthlySpec(percent)));
    }

    @Test
    void trueUpUsesEnabledSupportedFormulaAndNegativeHandling() {
        assertDoesNotThrow(() -> service.validateSpec(advanceTrueUpSpec(
                new TrueUp(true, "FY_EARNED_MINUS_ADVANCES", NegativeHandling.WRITE_OFF))));
        assertMessageContains("enabled", () -> service.validateSpec(advanceTrueUpSpec(
                new TrueUp(false, "FY_EARNED_MINUS_ADVANCES", NegativeHandling.WRITE_OFF))));
        assertMessageContains("FY_EARNED_MINUS_ADVANCES", () -> service.validateSpec(advanceTrueUpSpec(
                new TrueUp(true, "OTHER", NegativeHandling.WRITE_OFF))));
        assertMessageContains("negativeHandling", () -> service.validateSpec(advanceTrueUpSpec(
                new TrueUp(true, "FY_EARNED_MINUS_ADVANCES", null))));
    }

    @Test
    void replacementIsNullOrYpot_andOuterAndInnerMustMatch() {
        Spec unsupported = copy(yearlySpec(), "FISCAL_YEAR_SUM", yearlySpec().schedule(),
                yearlySpec().proRating(), yearlySpec().tierTable(), null, "OTHER");
        assertMessageContains("null or YPOT", () -> service.validateSpec(unsupported));

        IndividualBonusRuleRequest mismatch = new IndividualBonusRuleRequest("employee", "Bonus",
                LocalDate.of(2026, 7, 1), null, null, true, yearlySpec());
        assertMessageContains("exactly match", () -> service.validateRequest(mismatch));
    }

    @Test
    void formulaOwnsProrating() {
        Spec bad = copy(yearlySpec(), "FISCAL_YEAR_SUM", yearlySpec().schedule(), new ProRating(true),
                yearlySpec().tierTable(), "tier(production)", "YPOT");
        assertMessageContains("formula owns pro-rating", () -> service.validateSpec(bad));

        Spec valid = copy(yearlySpec(), "FISCAL_YEAR_SUM", yearlySpec().schedule(), new ProRating(false),
                yearlySpec().tierTable(), "tier(production)", "YPOT");
        assertDoesNotThrow(() -> service.validateSpec(valid));
    }

    private static Spec yearlySpec() {
        return new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", tiers(),
                new ProRating(true), null, false, "YPOT",
                new Schedule(Cadence.YEARLY, new Yearly(1), null, null), null);
    }

    private static Spec monthlySpec(Advance advance) {
        return new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", tiers(),
                new ProRating(false), null, false, null,
                new Schedule(Cadence.MONTHLY, null, advance, null), null);
    }

    private static Spec advanceTrueUpSpec(TrueUp trueUp) {
        return new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM", tiers(),
                new ProRating(false), null, false, null,
                new Schedule(Cadence.MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP, new Yearly(1), fixedAdvance(), trueUp),
                null);
    }

    private static Advance fixedAdvance() {
        return new Advance(Vehicle.MONTHLY_LUMP_SUM, AdvanceType.FIXED,
                bd("5000"), null, "EMPLOYED_IN_FY");
    }

    private static List<Tier> tiers() {
        return List.of(new Tier(BigDecimal.ZERO, bd("1000000"), BigDecimal.ZERO),
                new Tier(bd("1000000"), null, bd("0.2")));
    }

    private static Spec copy(Spec source, String aggregation, Schedule schedule, ProRating proRating,
                             List<Tier> tiers, String formula, String replaces) {
        return new Spec(source.basis(), aggregation, tiers, proRating, source.cap(), source.pension(),
                replaces, schedule, formula);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertMessageContains(String expected, Runnable validation) {
        BadRequestException failure = assertThrows(BadRequestException.class, validation::run);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}

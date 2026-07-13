package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Aggregation;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlySchedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyVehicle;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.StepBand;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Closed grammar, precision rules, and fixed-step selection for calendar-month bonus specs. */
public final class IndividualBonusMonthlySpecValidator {

    public static final int MAX_STEP_BANDS = 100;
    private static final int THRESHOLD_MAX_PRECISION = 18;
    private static final int THRESHOLD_MAX_SCALE = 6;
    private static final int MONEY_MAX_PRECISION = 15;
    private static final int MONEY_MAX_SCALE = 2;

    private IndividualBonusMonthlySpecValidator() {
    }

    /** True when any monthly-only discriminator is present; near-matches must fail, never fall into FY code. */
    public static boolean isMonthlyCandidate(Spec spec) {
        if (spec == null) return false;
        return Aggregation.CALENDAR_MONTH.name().equals(spec.aggregation())
                || spec.stepTable() != null
                || spec.expectedBaseSalary() != null
                || (spec.schedule() != null && spec.schedule().monthly() != null);
    }

    /** True only for a fully discriminated monthly spec. Validation still has to be run before evaluation. */
    public static boolean isMonthlyGrammar(Spec spec) {
        return spec != null
                && Aggregation.CALENDAR_MONTH.name().equals(spec.aggregation())
                && spec.stepTable() != null;
    }

    public static void validate(Spec spec) {
        require(Aggregation.CALENDAR_MONTH.name().equals(spec.aggregation()),
                "MONTHLY_AGGREGATION_REQUIRED", "spec.aggregation",
                "must be CALENDAR_MONTH when monthly fields are present");
        require(spec.basis() == Basis.UTILIZATION,
                "MONTHLY_BASIS_REQUIRED", "spec.basis", "must be UTILIZATION");
        require(spec.tierTable() == null,
                "MONTHLY_TIER_TABLE_FORBIDDEN", "spec.tierTable", "must be null");
        require(spec.formula() == null,
                "MONTHLY_FORMULA_FORBIDDEN", "spec.formula", "must be null");
        require(spec.proRating() == null,
                "MONTHLY_PRORATING_FORBIDDEN", "spec.proRating", "must be null");
        require(spec.cap() == null,
                "MONTHLY_CAP_FORBIDDEN", "spec.cap", "must be null");
        require(spec.replaces() == null,
                "MONTHLY_REPLACEMENT_FORBIDDEN", "spec.replaces", "must be null");

        validateMoney(spec.expectedBaseSalary(), "spec.expectedBaseSalary", true,
                "EXPECTED_BASE_SALARY_INVALID");

        require(spec.schedule() != null,
                "MONTHLY_SCHEDULE_REQUIRED", "spec.schedule", "is required");
        require(spec.schedule().cadence() == Cadence.MONTHLY,
                "MONTHLY_CADENCE_REQUIRED", "spec.schedule.cadence", "must be MONTHLY");
        MonthlySchedule monthly = spec.schedule().monthly();
        require(monthly != null,
                "MONTHLY_SCHEDULE_REQUIRED", "spec.schedule.monthly", "is required");
        require(monthly.vehicle() == MonthlyVehicle.MONTHLY_LUMP_SUM,
                "MONTHLY_VEHICLE_REQUIRED", "spec.schedule.monthly.vehicle",
                "must be MONTHLY_LUMP_SUM");
        require(monthly.payMonthOffset() >= 1 && monthly.payMonthOffset() <= 12,
                "PAY_MONTH_OFFSET_INVALID", "spec.schedule.monthly.payMonthOffset",
                "must be within [1, 12]");
        require(spec.schedule().yearly() == null
                        && spec.schedule().advance() == null
                        && spec.schedule().trueUp() == null,
                "MONTHLY_FY_BRANCH_FORBIDDEN", "spec.schedule",
                "yearly, advance, and trueUp must be null");

        validateStepBands(spec.stepTable());
    }

    public static void validateStepBands(List<StepBand> bands) {
        require(bands != null && !bands.isEmpty(),
                "STEP_TABLE_REQUIRED", "spec.stepTable", "must be a non-empty array");
        require(bands.size() <= MAX_STEP_BANDS,
                "STEP_TABLE_TOO_LARGE", "spec.stepTable", "must contain at most " + MAX_STEP_BANDS + " bands");

        StepBand first = bands.getFirst();
        require(first != null && first.from() != null && first.from().compareTo(BigDecimal.ZERO) == 0,
                "STEP_TABLE_FIRST_FROM", "spec.stepTable[0].from", "must equal 0");

        for (int i = 0; i < bands.size(); i++) {
            StepBand band = bands.get(i);
            String path = "spec.stepTable[" + i + "]";
            require(band != null, "STEP_BAND_REQUIRED", path, "must be an object");
            validateThreshold(band.from(), path + ".from", true);
            validateMoney(band.amount(), path + ".amount", false, "STEP_AMOUNT_INVALID");

            boolean last = i == bands.size() - 1;
            if (last) {
                require(band.to() == null,
                        "STEP_TABLE_FINAL_OPEN", path + ".to", "must be null for the final band");
            } else {
                validateThreshold(band.to(), path + ".to", true);
                require(band.to().compareTo(band.from()) > 0,
                        "STEP_BAND_EMPTY", path + ".to", "must be greater than from");
                StepBand next = bands.get(i + 1);
                require(next != null && next.from() != null,
                        "STEP_BAND_REQUIRED", "spec.stepTable[" + (i + 1) + "].from", "is required");
                int boundary = next.from().compareTo(band.to());
                require(boundary == 0,
                        boundary < 0 ? "STEP_TABLE_OVERLAP" : "STEP_TABLE_GAP",
                        "spec.stepTable[" + (i + 1) + "].from",
                        "must equal the preceding to boundary");
            }
        }
    }

    /** Selects exactly one inclusive-from/exclusive-to band; negative values are clamped only for selection. */
    public static StepBand selectStep(List<StepBand> bands, BigDecimal rawUtilization) {
        if (bands == null || rawUtilization == null) {
            throw new IllegalStateException("STEP_TABLE_NOT_TOTAL");
        }
        BigDecimal selection = rawUtilization.max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
        StepBand selected = null;
        for (StepBand band : bands) {
            if (band != null && band.from() != null
                    && band.from().compareTo(selection) <= 0
                    && (band.to() == null || selection.compareTo(band.to()) < 0)) {
                if (selected != null) throw new IllegalStateException("STEP_TABLE_NOT_TOTAL");
                selected = band;
            }
        }
        if (selected == null) throw new IllegalStateException("STEP_TABLE_NOT_TOTAL");
        return selected;
    }

    private static void validateThreshold(BigDecimal value, String path, boolean required) {
        if (value == null) {
            require(!required, "STEP_THRESHOLD_REQUIRED", path, "is required");
            return;
        }
        require(value.signum() >= 0,
                "STEP_THRESHOLD_NEGATIVE", path, "must be nonnegative");
        require(value.precision() <= THRESHOLD_MAX_PRECISION && value.scale() <= THRESHOLD_MAX_SCALE,
                "STEP_THRESHOLD_PRECISION", path,
                "must have precision <= " + THRESHOLD_MAX_PRECISION + " and scale <= " + THRESHOLD_MAX_SCALE);
    }

    private static void validateMoney(BigDecimal value, String path, boolean positive, String code) {
        require(value != null, code, path, "is required");
        require(positive ? value.signum() > 0 : value.signum() >= 0,
                code, path, positive ? "must be greater than 0" : "must be nonnegative");
        require(value.precision() <= MONEY_MAX_PRECISION && value.scale() <= MONEY_MAX_SCALE,
                code, path,
                "must have precision <= " + MONEY_MAX_PRECISION + " and scale <= " + MONEY_MAX_SCALE);
    }

    private static void require(boolean condition, String code, String path, String message) {
        if (!condition) {
            throw new IndividualBonusException(400, code, path + " " + message, path);
        }
    }
}

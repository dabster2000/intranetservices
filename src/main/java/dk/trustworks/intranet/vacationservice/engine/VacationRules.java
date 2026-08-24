package dk.trustworks.intranet.vacationservice.engine;

import java.time.LocalDate;
import java.time.Month;

/**
 * Ferieloven (post-2020 samtidighedsferie) calendar math and constants.
 *
 * <p>A vacation year ("ferieår") is identified by its September start-year:
 * ferieår 2025 = 1 Sep 2025 – 31 Aug 2026, with a usage window
 * ("afholdelsesperiode") extending to 31 Dec 2026 — 16 months.</p>
 */
public final class VacationRules {

    /** Standard workday used to convert timesheet hours to vacation days. */
    public static final double HOURS_PER_DAY = 7.4;

    /**
     * The first ferieår the ledger models. Matches the oldest year in the
     * Danløn feriepengeforpligtelse baseline (ferieår 2024).
     */
    public static final int LEDGER_EPOCH_FERIEAAR = 2024;

    /**
     * The statutory 4 weeks that must be taken within the usage window and can
     * never be transferred (ferieloven §§ 21–22). Only days beyond these 20
     * can move to the next ferieår or be paid out in March.
     */
    public static final double STATUTORY_PROTECTED_DAYS = 20.0;

    private VacationRules() {
    }

    /** The ferieår a date falls in (by earning period, Sep–Aug). */
    public static int ferieaarOf(LocalDate date) {
        return date.getMonthValue() >= 9 ? date.getYear() : date.getYear() - 1;
    }

    public static LocalDate startOf(int ferieaar) {
        return LocalDate.of(ferieaar, Month.SEPTEMBER, 1);
    }

    /** Last day of the earning period (31 Aug the following year). */
    public static LocalDate endOfEarning(int ferieaar) {
        return LocalDate.of(ferieaar + 1, Month.AUGUST, 31);
    }

    /** Last day of the usage window (31 Dec the following year). */
    public static LocalDate endOfUse(int ferieaar) {
        return LocalDate.of(ferieaar + 1, Month.DECEMBER, 31);
    }

    /** True while the usage window is open on the given date. */
    public static boolean isOpen(int ferieaar, LocalDate date) {
        return !date.isBefore(startOf(ferieaar)) && !date.isAfter(endOfUse(ferieaar));
    }

    /** Display label, e.g. "2025/26". */
    public static String label(int ferieaar) {
        return ferieaar + "/" + String.format("%02d", (ferieaar + 1) % 100);
    }

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure, DB-free helpers for the Client Status dashboard (unit-tested). */
public final class ClientStatusMath {

    private ClientStatusMath() {}

    private static final double FULL_LOWER = 0.98d;
    private static final double FULL_UPPER = 1.02d;
    private static final double INVOICED_EPSILON = 0.01d;

    /**
     * Day-of-month by which a billing month is considered fully invoiced. A month's work is not
     * fully billed until invoicing for it completes early in the following month, so any month
     * whose cutoff has not yet passed is treated as provisional.
     */
    private static final int INVOICING_CUTOFF_DAY = 10;

    /** Classify one client-month cell from its expected and invoiced amounts. */
    public static ClientStatusCellState classify(double expected, double invoiced) {
        if (expected <= 0d) {
            return invoiced > 0d ? ClientStatusCellState.OVER : ClientStatusCellState.NO_ACTIVITY;
        }
        double ratio = invoiced / expected;
        if (ratio < INVOICED_EPSILON) return ClientStatusCellState.NOT_INVOICED;
        if (ratio < FULL_LOWER) return ClientStatusCellState.PARTIAL;
        if (ratio <= FULL_UPPER) return ClientStatusCellState.FULL;
        return ClientStatusCellState.OVER;
    }

    /** 12 month keys ("YYYYMM"), oldest→newest, for the TTM window ending at {@code end}. */
    public static List<String> ttmMonthKeys(YearMonth end) {
        List<String> keys = new ArrayList<>(12);
        YearMonth start = end.minusMonths(11);
        for (int i = 0; i < 12; i++) {
            YearMonth ym = start.plusMonths(i);
            keys.add(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()));
        }
        return keys;
    }

    /** Inclusive first day of the TTM window. */
    public static LocalDate ttmFromDate(YearMonth end) {
        return end.minusMonths(11).atDay(1);
    }

    /** Exclusive upper bound (first day of the month after {@code end}). */
    public static LocalDate ttmToDateExclusive(YearMonth end) {
        return end.plusMonths(1).atDay(1);
    }

    /** Inclusive lower period key ({@code year*100+month}) of the TTM window. */
    public static int ttmFromPeriod(YearMonth end) {
        YearMonth start = end.minusMonths(11);
        return start.getYear() * 100 + start.getMonthValue();
    }

    /** Inclusive upper period key ({@code year*100+month}) of the TTM window. */
    public static int ttmToPeriod(YearMonth end) {
        return end.getYear() * 100 + end.getMonthValue();
    }

    /**
     * Subset of {@code months} ("YYYYMM") that are still <em>provisional</em> on {@code today} —
     * i.e. not yet fully invoiced because {@code today} falls before the {@link #INVOICING_CUTOFF_DAY}th
     * of the month after them. Provisional months are still shown in the heatmap but are excluded
     * from the per-client row totals and the dashboard summary, since the current month is never
     * fully invoiced until early in the following month.
     */
    public static Set<String> provisionalMonthKeys(List<String> months, LocalDate today) {
        Set<String> provisional = new HashSet<>();
        for (String mk : months) {
            YearMonth ym = YearMonth.of(
                    Integer.parseInt(mk.substring(0, 4)),
                    Integer.parseInt(mk.substring(4, 6)));
            if (today.isBefore(ym.plusMonths(1).atDay(INVOICING_CUTOFF_DAY))) {
                provisional.add(mk);
            }
        }
        return provisional;
    }
}

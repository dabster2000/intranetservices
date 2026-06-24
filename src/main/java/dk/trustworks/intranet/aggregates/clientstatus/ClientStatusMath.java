package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCellState;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Pure, DB-free helpers for the Client Status dashboard (unit-tested). */
public final class ClientStatusMath {

    private ClientStatusMath() {}

    private static final double FULL_LOWER = 0.98d;
    private static final double FULL_UPPER = 1.02d;
    private static final double INVOICED_EPSILON = 0.01d;

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
}

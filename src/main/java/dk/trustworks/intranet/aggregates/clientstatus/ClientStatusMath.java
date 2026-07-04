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
     * Tolerance (kr) beyond which an approval snapshot is considered stale ("drifted"). Once a
     * client-month cell is approved, its expected/invoiced values are frozen in a snapshot; if the
     * live values later move by more than this on either axis, the cell is flagged so it re-enters
     * the controlling worklist and the AM brief.
     */
    public static final double DRIFT_TOLERANCE_DKK = 1_000d;

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

    /**
     * True when a single {@code monthKey} ("YYYYMM") is still provisional on {@code today} — i.e.
     * not yet fully invoiced because {@code today} falls before the {@link #INVOICING_CUTOFF_DAY}th
     * of the following month. Used to reject controlling actions on months that are not yet final.
     */
    public static boolean isProvisional(String monthKey, LocalDate today) {
        YearMonth ym = YearMonth.of(
                Integer.parseInt(monthKey.substring(0, 4)),
                Integer.parseInt(monthKey.substring(4, 6)));
        return today.isBefore(ym.plusMonths(1).atDay(INVOICING_CUTOFF_DAY));
    }

    /**
     * True when an approval snapshot has drifted from the live cell values beyond
     * {@link #DRIFT_TOLERANCE_DKK} on either the expected or the invoiced axis. Only meaningful for
     * approved cells: if {@code approved} is false the snapshot is irrelevant and this returns false.
     *
     * @param approved         whether an approval snapshot exists for the cell
     * @param approvedExpected  frozen expected value at approval time (nullable → treated as 0)
     * @param approvedInvoiced  frozen invoiced value at approval time (nullable → treated as 0)
     * @param currentExpected   live expected value now
     * @param currentInvoiced   live invoiced value now
     */
    public static boolean isDrifted(boolean approved,
                                    Double approvedExpected, Double approvedInvoiced,
                                    double currentExpected, double currentInvoiced) {
        if (!approved) return false;
        double snapExpected = approvedExpected == null ? 0d : approvedExpected;
        double snapInvoiced = approvedInvoiced == null ? 0d : approvedInvoiced;
        return Math.abs(currentExpected - snapExpected) > DRIFT_TOLERANCE_DKK
                || Math.abs(currentInvoiced - snapInvoiced) > DRIFT_TOLERANCE_DKK;
    }

    /**
     * A cell is <em>effectively approved</em> when an approval snapshot exists AND it has not
     * drifted. Effectively-approved months are excluded from row gap counts and from the AM brief.
     */
    public static boolean isEffectivelyApproved(boolean approved, boolean drift) {
        return approved && !drift;
    }

    /**
     * Whether a cell counts as a "gap" for the controlling worklist. Gaps are under-billed months
     * (NOT_INVOICED or PARTIAL) that are neither provisional nor effectively approved. A drifted
     * approval re-counts as a gap because its snapshot no longer reflects reality.
     *
     * @param state        the cell's coverage state
     * @param provisional  whether the month is still provisional
     * @param approved     whether an approval snapshot exists
     * @param drift        whether that snapshot has drifted
     */
    public static boolean countsAsGap(ClientStatusCellState state, boolean provisional,
                                      boolean approved, boolean drift) {
        if (provisional) return false;
        if (isEffectivelyApproved(approved, drift)) return false;
        return state == ClientStatusCellState.NOT_INVOICED || state == ClientStatusCellState.PARTIAL;
    }

    /**
     * Whether a cell is eligible for bulk approval in the given scope. Eligible cells are
     * non-provisional, have activity ({@code state != NO_ACTIVITY}), and are not already effectively
     * approved (a drifted approval is re-eligible). {@code fullOnly} additionally requires the cell
     * to be classified {@link ClientStatusCellState#FULL}.
     *
     * @param state        the cell's coverage state
     * @param provisional  whether the month is still provisional
     * @param approved     whether an approval snapshot already exists
     * @param drift        whether that snapshot has drifted
     * @param fullOnly     true = FULL_ONLY scope; false = ALL_REMAINING scope
     */
    public static boolean isBulkApprovable(ClientStatusCellState state, boolean provisional,
                                           boolean approved, boolean drift, boolean fullOnly) {
        if (provisional) return false;
        if (state == ClientStatusCellState.NO_ACTIVITY) return false;
        if (isEffectivelyApproved(approved, drift)) return false;
        if (fullOnly && state != ClientStatusCellState.FULL) return false;
        return true;
    }
}

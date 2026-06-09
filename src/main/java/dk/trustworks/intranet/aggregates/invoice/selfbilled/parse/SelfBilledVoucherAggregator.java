package dk.trustworks.intranet.aggregates.invoice.selfbilled.parse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nets self-billed lines BY VOUCHER (Decision D10). Each e-conomic voucher is one
 * (code, work-period); correction lines (Forkert/Bogført) carry no code/period of
 * their own and are folded in via the voucher. Pure (no DB, no CDI).
 *
 * <p>A voucher is {@code resolved} iff exactly one (code, work-period) appears among
 * its parseable lines. Zero parseable lines (a pure-correction orphan) or more than
 * one (code, work-period) → unresolved → review queue. The signed amount is the sum
 * of ALL lines of the voucher (so a duplicate booking reversed by an unparseable
 * "Bogført 2 x" nets to its true value, never double). The persistence layer stamps
 * this resolved (code, work-period) onto every sibling row so SQL SUMs include the
 * corrections (Task D2).
 */
public final class SelfBilledVoucherAggregator {

    private SelfBilledVoucherAggregator() {}

    /** One e-conomic debtor line. {@code parsed} is null for a correction/unparseable line. */
    public record LineInput(int account, int voucher, long entry, BigDecimal signedAmount,
                            ParsedLine parsed, String text) {}

    /**
     * Voucher-netted result. When {@code resolved} is false, code/workYear/workMonth are UNSET
     * (null/0) and MUST NOT be read — always check {@code resolved()} first (Task D2 does).
     */
    public record VoucherNet(int account, int voucher, String code, int workYear, int workMonth,
                             BigDecimal signedAmount, boolean resolved, List<Long> entries) {}

    public static List<VoucherNet> aggregate(List<LineInput> lines) {
        Map<String, List<LineInput>> byVoucher = new LinkedHashMap<>();
        for (LineInput l : lines) {
            byVoucher.computeIfAbsent(l.account() + "|" + l.voucher(), k -> new ArrayList<>()).add(l);
        }

        List<VoucherNet> out = new ArrayList<>(byVoucher.size());
        for (List<LineInput> group : byVoucher.values()) {
            int account = group.get(0).account();
            int voucher = group.get(0).voucher();

            BigDecimal sum = BigDecimal.ZERO;
            List<Long> entries = new ArrayList<>(group.size());
            Map<String, ParsedLine> distinct = new LinkedHashMap<>();
            for (LineInput l : group) {
                sum = sum.add(l.signedAmount());
                entries.add(l.entry());
                if (l.parsed() != null) {
                    ParsedLine p = l.parsed();
                    distinct.putIfAbsent(p.code() + "|" + p.workYear() + "|" + p.workMonth(), p);
                }
            }
            entries.sort(Comparator.naturalOrder());

            if (distinct.size() == 1) {
                ParsedLine p = distinct.values().iterator().next();
                out.add(new VoucherNet(account, voucher, p.code(), p.workYear(), p.workMonth(), sum, true, entries));
            } else {
                out.add(new VoucherNet(account, voucher, null, 0, 0, sum, false, entries));
            }
        }
        return out;
    }
}

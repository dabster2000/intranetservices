package dk.trustworks.intranet.aggregates.invoice.selfbilled.parse;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfBilledVoucherAggregatorTest {

    private static SelfBilledVoucherAggregator.LineInput line(int voucher, long entry, String text, String amt) {
        return new SelfBilledVoucherAggregator.LineInput(2106, voucher, entry,
                new BigDecimal(amt), SelfBilledTextParser.parse(text).orElse(null), text);
    }

    @Test void duplicateBooking_reversal_nets_to_single_amount() {
        // Verified TD 07-2025: real booking (v1771) + duplicate (v2291) reversed by an
        // unparseable "Bogført 2 x" that shares v2291. Voucher-net must yield -55,963.44 total,
        // never -111,926.88. (Two vouchers, same TD 07-2025.)
        List<SelfBilledVoucherAggregator.LineInput> in = List.of(
                line(1771, 128956, "Faktura: 17-869 - 07-2025 TD", "-55963.44"),
                line(2291, 134124, "Faktura: 5105723572 - 07-2025 TD", "-55963.44"),
                line(2291, 134647, "Bogført 2 x ", "55963.44"));
        List<SelfBilledVoucherAggregator.VoucherNet> out = SelfBilledVoucherAggregator.aggregate(in);

        assertEquals(2, out.size());
        BigDecimal total = out.stream().map(SelfBilledVoucherAggregator.VoucherNet::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("-55963.44"), total);
        assertTrue(out.stream().allMatch(v -> v.resolved() && v.code().equals("TD")
                && v.workYear() == 2025 && v.workMonth() == 7));
    }

    @Test void correction_with_parseable_sibling_inherits_period() {
        // MHB 09-2025: real -162,537.65 + extra -78,647.26 + "Forkert beløb" +162,537.65, all v1924.
        List<SelfBilledVoucherAggregator.LineInput> in = List.of(
                line(1924, 128943, "Faktura: 5105733453 - 09-2025 MHB", "-162537.65"),
                line(1924, 129029, "Faktura 5105733453 - 09-2025 MHB", "-78647.26"),
                line(1924, 129031, "Forkert beløb ", "162537.65"));
        List<SelfBilledVoucherAggregator.VoucherNet> out = SelfBilledVoucherAggregator.aggregate(in);
        assertEquals(1, out.size());
        assertEquals(new BigDecimal("-78647.26"), out.get(0).signedAmount());
        assertTrue(out.get(0).resolved());
    }

    @Test void voucher_with_no_parseable_line_is_unresolved() {
        List<SelfBilledVoucherAggregator.LineInput> in = List.of(
                line(9999, 1, "Forkert kt ", "100.00"));
        SelfBilledVoucherAggregator.VoucherNet v = SelfBilledVoucherAggregator.aggregate(in).get(0);
        assertFalse(v.resolved());
        assertNull(v.code());
    }

    @Test void twoParseableLinesSamePeriod_resolved_sumsBoth() {
        List<SelfBilledVoucherAggregator.LineInput> in = List.of(
                line(5, 1, "Faktura: INV-A - 07-2025 TD", "-100.00"),
                line(5, 2, "Faktura: INV-B - 07-2025 TD", "-200.00"));
        SelfBilledVoucherAggregator.VoucherNet v = SelfBilledVoucherAggregator.aggregate(in).get(0);
        assertTrue(v.resolved());
        assertEquals("TD", v.code());
        assertEquals(new BigDecimal("-300.00"), v.signedAmount());
        assertEquals(2, v.entries().size());
    }

    @Test void multiCode_voucher_is_unresolved_hardError() {
        List<SelfBilledVoucherAggregator.LineInput> in = List.of(
                line(7, 1, "Faktura: 1 - 07-2025 TD", "-100.00"),
                line(7, 2, "Faktura: 2 - 07-2025 FSP", "-200.00"));
        SelfBilledVoucherAggregator.VoucherNet v = SelfBilledVoucherAggregator.aggregate(in).get(0);
        assertFalse(v.resolved());
    }
}

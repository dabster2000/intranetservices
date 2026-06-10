package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.AssignmentSourceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledAssignment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfBilledAttributionMirrorTest {

    private static SelfBilledAssignment assignment(String consultant, String share) {
        return assignment(consultant, 2025, 8, share);
    }

    private static SelfBilledAssignment assignment(String consultant, int year, int month, String share) {
        SelfBilledAssignment a = new SelfBilledAssignment();
        a.uuid = "a-" + consultant + "-" + year + "-" + month;
        a.consultantUuid = consultant;
        a.workYear = year; a.workMonth = month;
        a.shareAmount = new BigDecimal(share);    // signed as posted (revenue negative)
        a.source = AssignmentSourceType.HUMAN;
        return a;
    }

    @Test
    void single_assignment_mirrors_full_phantom_total() {
        var rows = SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("153525.00"),               // phantom item total (positive revenue)
                new BigDecimal("-153525.00"),              // voucher net (signed)
                List.of(assignment("michelle", "-153525.00")));
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("153525.00"), rows.get(0).attributedAmount());
        assertEquals(0, new BigDecimal("100.00").compareTo(rows.get(0).sharePct()));
    }

    @Test
    void split_sums_exactly_to_phantom_total_with_rounding_on_last_row() {
        var rows = SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("100.00"), new BigDecimal("-300.00"),
                List.of(assignment("a", "-100.00"), assignment("b", "-100.00"), assignment("c", "-100.00")));
        BigDecimal sum = rows.stream().map(SelfBilledAttributionMirror.MirrorRow::attributedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("100.00").compareTo(sum), "rounding absorbed by last row");
        assertEquals(3, rows.size());
    }

    @Test
    void zero_net_or_no_assignments_yield_nothing() {
        assertTrue(SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("100.00"), BigDecimal.ZERO, List.of(assignment("a", "-100"))).isEmpty());
        assertTrue(SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("100.00"), new BigDecimal("-100.00"), List.of()).isEmpty());
    }

    @Test
    void negative_phantom_total_credit_note_is_sign_safe() {
        // Credit note: voucher net positive, phantom total negative — the phantom total carries the sign.
        var rows = SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("-100.00"),                 // phantom item total (credit note)
                new BigDecimal("100.00"),                  // voucher net (signed as posted)
                List.of(assignment("a", "100.00")));
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("-100.00").compareTo(rows.get(0).attributedAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(rows.get(0).sharePct()));
    }

    @Test
    void same_consultant_split_collapses_to_one_row_summed() {
        // A voucher split to the SAME consultant across two work periods. uq_iia_item_consultant
        // forbids two attribution rows on one item for one consultant, so the two shares must
        // collapse into ONE MirrorRow carrying their sum (the full phantom total here).
        var rows = SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("153525.00"),               // phantom item total
                new BigDecimal("-153525.00"),              // voucher net (signed)
                List.of(assignment("michelle", 2025, 8, "-100000.00"),
                        assignment("michelle", 2025, 9, "-53525.00")));
        assertEquals(1, rows.size(), "two same-consultant assignments collapse to one row");
        assertEquals("michelle", rows.get(0).consultantUuid());
        assertEquals(0, new BigDecimal("153525.00").compareTo(rows.get(0).attributedAmount()),
                "summed share maps to the full phantom total");
        assertEquals(0, new BigDecimal("100.00").compareTo(rows.get(0).sharePct()));
    }

    @Test
    void drifted_assignments_use_proportional_mode_no_absorption() {
        // Stale set: Σ shares (-100) != voucher net (-300) — must NOT dump the remainder
        // on the last row; proportional mode gives the honest 1/3 share instead.
        var rows = SelfBilledAttributionMirror.computeMirrorRows(
                new BigDecimal("100.00"), new BigDecimal("-300.00"),
                List.of(assignment("a", "-100.00")));
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("33.33").compareTo(rows.get(0).attributedAmount()),
                "proportional mode: fraction x total, not the full phantom total");
    }
}

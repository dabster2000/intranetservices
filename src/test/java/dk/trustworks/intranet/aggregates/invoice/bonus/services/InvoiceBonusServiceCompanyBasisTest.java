package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService.BasisLineRow;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for {@link InvoiceBonusService#approvedUnconsumedBasisByCompany}: the SQL loader and the
 * status-based company resolver are stubbed through the service's {@code protected} hooks, so every
 * attribution rule under test is the real one.
 */
class InvoiceBonusServiceCompanyBasisTest {

    private static final String PARTNER = "partner-uuid";
    private static final LocalDate FROM = LocalDate.of(2025, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2025, 10, 15);

    private static final String TW = "tw";
    private static final String TWT = "twt";
    private static final String TWC = "twc";

    private static final class TestableService extends InvoiceBonusService {
        List<BasisLineRow> rows = List.of();
        Map<String, String> companyByConsultant = new HashMap<>();
        int loaderCalls;

        @Override
        protected List<BasisLineRow> findApprovedUnconsumedBasisLines(String userUuid, LocalDate from, LocalDate to) {
            loaderCalls++;
            assertEquals(PARTNER, userUuid);
            assertEquals(FROM, from);
            assertEquals(TO, to);
            return rows;
        }

        @Override
        protected String companyUuidAt(String userUuid, LocalDate date, Map<String, Map<LocalDate, UserStatus>> cache) {
            assertEquals(INVOICE_DATE, date, "company must be resolved at invoice date");
            return companyByConsultant.get(userUuid);
        }
    }

    private static BasisLineRow line(String bonus, double computed, String invoiceCompany,
                                     String consultant, String origin, double selected) {
        return new BasisLineRow(bonus, computed, "inv-" + bonus, INVOICE_DATE, invoiceCompany,
                consultant, origin, selected);
    }

    @Test
    void attributesConsultantLinesByCompany_andSpreadsFeesAndAdjustmentsProRata() {
        TestableService svc = new TestableService();
        svc.companyByConsultant.put("c-tw", TW);
        svc.companyByConsultant.put("c-twt", TWT);
        // Bonus A: 181,000 = TW 100k@100% + TWT 100k@80% + fee 5k (no consultant) + CALCULATED -4k;
        //          the partner's own line at 0% is skipped.
        svc.rows = List.of(
                line("A", 181_000, TW, "c-tw", "BASE", 100_000),
                line("A", 181_000, TW, "c-twt", "BASE", 80_000),
                line("A", 181_000, TW, "partner-uuid", "BASE", 0),
                line("A", 181_000, TW, null, "BASE", 5_000),
                line("A", 181_000, TW, null, "CALCULATED", -4_000)
        );

        Map<String, Double> out = svc.approvedUnconsumedBasisByCompany(PARTNER, FROM, TO);

        assertEquals(1, svc.loaderCalls);
        assertEquals(2, out.size());
        assertEquals(181_000.0 * 100 / 180, out.get(TW), 0.001);
        assertEquals(181_000.0 * 80 / 180, out.get(TWT), 0.001);
    }

    @Test
    void feeOnlyInvoice_andRowWithoutLines_goToTheIssuingCompany() {
        TestableService svc = new TestableService();
        svc.rows = List.of(
                line("B", 20_000, TWT, null, "BASE", 20_000),        // fixed-price item, no consultant
                new BasisLineRow("D", 7_000, "inv-D", INVOICE_DATE, TW, null, null, 0.0) // no line selections
        );

        Map<String, Double> out = svc.approvedUnconsumedBasisByCompany(PARTNER, FROM, TO);

        assertEquals(20_000.0, out.get(TWT), 0.001);
        assertEquals(7_000.0, out.get(TW), 0.001);
    }

    @Test
    void unresolvableConsultantCompany_fallsBackToIssuingCompany_andUnknownWhenNoIssuer() {
        TestableService svc = new TestableService();
        svc.rows = List.of(
                line("C", 50_000, TWC, "c-nostatus", "BASE", 50_000),
                line("E", 1_000, null, "c-nostatus", "BASE", 1_000)
        );

        Map<String, Double> out = svc.approvedUnconsumedBasisByCompany(PARTNER, FROM, TO);

        assertEquals(50_000.0, out.get(TWC), 0.001);
        assertEquals(1_000.0, out.get(InvoiceBonusService.UNKNOWN_COMPANY_UUID), 0.001);
    }

    /** CALCULATED lines carrying a consultant are still adjustments and never attributed directly. */
    @Test
    void calculatedLinesAreNeverAttributedDirectly() {
        TestableService svc = new TestableService();
        svc.companyByConsultant.put("c-tw", TW);
        svc.companyByConsultant.put("c-twt", TWT);
        svc.rows = List.of(
                line("F", 90_000, TW, "c-tw", "BASE", 100_000),
                line("F", 90_000, TW, "c-twt", "CALCULATED", -10_000)
        );

        Map<String, Double> out = svc.approvedUnconsumedBasisByCompany(PARTNER, FROM, TO);

        assertEquals(1, out.size());
        assertEquals(90_000.0, out.get(TW), 0.001);
    }

    @Test
    void sumsAcrossBonusRows_andEqualsTheApprovedTotal() {
        TestableService svc = new TestableService();
        svc.companyByConsultant.put("c-tw", TW);
        svc.companyByConsultant.put("c-twt", TWT);
        svc.rows = List.of(
                line("A", 181_000, TW, "c-tw", "BASE", 100_000),
                line("A", 181_000, TW, "c-twt", "BASE", 80_000),
                line("B", 20_000, TWT, null, "BASE", 20_000),
                line("G", -1_000, TW, "c-tw", "BASE", 750),          // credit note: negative computedAmount
                line("G", -1_000, TW, "c-twt", "BASE", 250)
        );

        Map<String, Double> out = svc.approvedUnconsumedBasisByCompany(PARTNER, FROM, TO);

        double total = out.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(181_000.0 + 20_000.0 - 1_000.0, total, 0.001);
        assertEquals(181_000.0 * 100 / 180 - 750.0, out.get(TW), 0.001);
        assertEquals(181_000.0 * 80 / 180 + 20_000.0 - 250.0, out.get(TWT), 0.001);
    }

    @Test
    void blankUserOrNoRows_yieldEmptyMap() {
        TestableService svc = new TestableService();
        assertTrue(svc.approvedUnconsumedBasisByCompany(" ", FROM, TO).isEmpty());
        assertEquals(0, svc.loaderCalls);
        assertTrue(svc.approvedUnconsumedBasisByCompany(PARTNER, FROM, TO).isEmpty());
        assertEquals(1, svc.loaderCalls);
    }
}

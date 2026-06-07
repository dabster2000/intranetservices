package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettlementDeltaCalculatorTest {

    private static final SettlementGroupKey KEY = new SettlementGroupKey("client-1", "debtor-1", 2026, 3);
    private static final Map<String, String> CN = Map.of("cons-1", "Jane Doe", "cons-2", "John Roe");
    private static final Map<String, String> CO = Map.of("issuer-A", "Cyber A/S", "issuer-B", "DigitalRizon", "debtor-1", "Technology A/S");

    private static SettlementDeltaCalculator.TargetLine t(String issuer, String cons, String amt) {
        return new SettlementDeltaCalculator.TargetLine(issuer, cons, new BigDecimal(amt));
    }
    private static SettlementDeltaCalculator.SettledLine s(String issuer, String cons, String amt) {
        return new SettlementDeltaCalculator.SettledLine(issuer, cons, new BigDecimal(amt));
    }

    @Test
    void firstSettlement_targetMinusZero_isFullPositiveDelta() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "50000.00")), List.of(), CN, CO, true);
        assertEquals(0, p.totalTarget().compareTo(new BigDecimal("50000.00")));
        assertEquals(0, p.totalSettled().compareTo(BigDecimal.ZERO));
        assertEquals(0, p.totalDelta().compareTo(new BigDecimal("50000.00")));
        assertEquals("Cyber A/S", p.issuers().get(0).issuerCompanyName());
        assertEquals("Jane Doe", p.issuers().get(0).consultants().get(0).consultantName());
    }

    @Test
    void supplementaryPhantom_targetGrows_deltaIsTheIncrement() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "60000.00")),
                List.of(s("issuer-A", "cons-1", "50000.00")), CN, CO, true);
        assertEquals(0, p.totalDelta().compareTo(new BigDecimal("10000.00")));
    }

    @Test
    void selfBillingCreditNote_targetDropsBelowSettled_negativeDelta() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "40000.00")),
                List.of(s("issuer-A", "cons-1", "60000.00")), CN, CO, true);
        assertEquals(0, p.totalDelta().compareTo(new BigDecimal("-20000.00")));
    }

    @Test
    void internalCreditNoted_settledBackToZero_deltaIsFullTargetAgain() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "40000.00")), List.of(), CN, CO, true);
        assertEquals(0, p.totalDelta().compareTo(new BigDecimal("40000.00")));
    }

    @Test
    void zeroDelta_isIdempotentNoOp() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "50000.00")),
                List.of(s("issuer-A", "cons-1", "50000.00")), CN, CO, true);
        assertEquals(0, p.totalDelta().compareTo(BigDecimal.ZERO));
        assertEquals(0, p.issuers().get(0).consultants().get(0).delta().compareTo(BigDecimal.ZERO));
    }

    @Test
    void multiIssuer_foldsIndependently() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1",
                List.of(t("issuer-A", "cons-1", "30000.00"), t("issuer-B", "cons-2", "20000.00")),
                List.of(s("issuer-A", "cons-1", "30000.00")), CN, CO, true);
        assertEquals(2, p.issuers().size());
        var a = p.issuers().stream().filter(i -> i.issuerCompanyUuid().equals("issuer-A")).findFirst().orElseThrow();
        var b = p.issuers().stream().filter(i -> i.issuerCompanyUuid().equals("issuer-B")).findFirst().orElseThrow();
        assertEquals(0, a.delta().compareTo(BigDecimal.ZERO));            // settled
        assertEquals(0, b.delta().compareTo(new BigDecimal("20000.00"))); // outstanding
        assertEquals(0, p.totalDelta().compareTo(new BigDecimal("20000.00")));
    }

    @Test
    void signedCreditNotePhantom_reducesTargetWithinConsultant() {
        // cons-1 had +50k then a -10k credit-note phantom -> net target 40k
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1",
                List.of(t("issuer-A", "cons-1", "50000.00"), t("issuer-A", "cons-1", "-10000.00")),
                List.of(), CN, CO, true);
        assertEquals(0, p.issuers().get(0).consultants().get(0).target().compareTo(new BigDecimal("40000.00")));
    }

    @Test
    void consultantOnlyInSettled_appearsWithNegativeDelta() {
        // a consultant was settled but target is now 0 (e.g. work removed) -> delta = -settled
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(), List.of(s("issuer-A", "cons-1", "5000.00")), CN, CO, true);
        assertEquals(0, p.totalDelta().compareTo(new BigDecimal("-5000.00")));
    }

    @Test
    void output_isDeterministicallyOrdered_byIssuerThenConsultant() {
        // Feed issuer-B before issuer-A, and cons-2 before cons-1 within issuer-A.
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1",
                List.of(t("issuer-B", "cons-2", "10000.00"),
                        t("issuer-A", "cons-2", "20000.00"),
                        t("issuer-A", "cons-1", "30000.00")),
                List.of(), CN, CO, true);
        // Issuers sorted by uuid: issuer-A before issuer-B.
        assertEquals("issuer-A", p.issuers().get(0).issuerCompanyUuid());
        assertEquals("issuer-B", p.issuers().get(1).issuerCompanyUuid());
        // Consultants under issuer-A sorted by uuid: cons-1 before cons-2.
        var a = p.issuers().get(0);
        assertEquals("cons-1", a.consultants().get(0).consultantUuid());
        assertEquals("cons-2", a.consultants().get(1).consultantUuid());
    }

    @Test
    void issuerRollup_sumsMultipleConsultants() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1",
                List.of(t("issuer-A", "cons-1", "30000.00"), t("issuer-A", "cons-2", "20000.00")),
                List.of(s("issuer-A", "cons-1", "5000.00")), CN, CO, true);
        var a = p.issuers().get(0);
        assertEquals(2, a.consultants().size());
        assertEquals(0, a.target().compareTo(new BigDecimal("50000.00")));   // 30000 + 20000
        assertEquals(0, a.settled().compareTo(new BigDecimal("5000.00")));
        assertEquals(0, a.delta().compareTo(new BigDecimal("45000.00")));    // 50000 - 5000
        // Issuer delta equals the sum of its consultant deltas (rollup, not overwrite).
        BigDecimal consDeltaSum = a.consultants().stream()
                .map(SettlementGroupPreview.ConsultantDelta::delta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, a.delta().compareTo(consDeltaSum));
    }

    @Test
    void rounding_isHalfUp_andScaleTwo() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "0.005")), List.of(), CN, CO, true);
        // 0.005 -> HALF_UP -> 0.01 (HALF_EVEN would give 0.00, so this locks the rounding mode).
        assertEquals(0, p.totalTarget().compareTo(new BigDecimal("0.01")), "HALF_UP rounds 0.005 up");
        assertEquals(2, p.totalTarget().scale(), "totals normalized to scale 2");
        assertEquals(2, p.issuers().get(0).consultants().get(0).target().scale(), "consultant figures scale 2");
    }

    @Test
    void names_fallBackToUuid_whenNameMapsMissing() {
        SettlementGroupPreview p = SettlementDeltaCalculator.compute(
                KEY, "debtor-1", List.of(t("issuer-A", "cons-1", "1000.00")),
                List.of(), Map.of(), Map.of(), true);   // empty name maps
        assertEquals("debtor-1", p.debtorCompanyName());
        assertEquals("issuer-A", p.issuers().get(0).issuerCompanyName());
        assertEquals("cons-1", p.issuers().get(0).consultants().get(0).consultantName());
    }
}

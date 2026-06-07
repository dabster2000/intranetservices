package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview.ConsultantDelta;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview.IssuerDelta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PhantomSettlementSettleDecisionTest {

    private static IssuerDelta issuer(String uuid, String deltaAmt) {
        BigDecimal d = new BigDecimal(deltaAmt);
        return new IssuerDelta(uuid, uuid + " A/S",
                List.of(new ConsultantDelta("c", "C", d, BigDecimal.ZERO, d)),
                d, BigDecimal.ZERO, d);
    }

    private static SettlementGroupPreview preview(IssuerDelta... issuers) {
        return new SettlementGroupPreview(new SettlementGroupKey("cl", "co", 2026, 3), "co", "Co",
                List.of(issuers), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    @Test
    void excludesZeroDeltaIssuers() {
        var p = preview(issuer("A", "1000.00"), issuer("B", "0.00"));
        var out = PhantomSettlementService.issuersToSettle(p, Set.of());
        assertEquals(1, out.size());
        assertEquals("A", out.get(0).issuerCompanyUuid());
    }

    @Test
    void includesNegativeDelta_creditNote() {
        var p = preview(issuer("A", "-2000.00"));
        assertEquals(1, PhantomSettlementService.issuersToSettle(p, Set.of()).size());
    }

    @Test
    void appliesIssuerFilter() {
        var p = preview(issuer("A", "1000.00"), issuer("B", "5000.00"));
        var out = PhantomSettlementService.issuersToSettle(p, Set.of("B"));
        assertEquals(1, out.size());
        assertEquals("B", out.get(0).issuerCompanyUuid());
    }

    @Test
    void emptyFilter_meansAllNonZero() {
        var p = preview(issuer("A", "1000.00"), issuer("B", "5000.00"));
        assertEquals(2, PhantomSettlementService.issuersToSettle(p, Set.of()).size());
    }

    @Test
    void nullFilter_meansAllNonZero() {
        var p = preview(issuer("A", "1000.00"), issuer("B", "5000.00"));
        assertEquals(2, PhantomSettlementService.issuersToSettle(p, null).size());
    }
}

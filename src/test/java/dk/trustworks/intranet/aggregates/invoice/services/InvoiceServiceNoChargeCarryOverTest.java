package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §4.4.2 (D2): a no-charge line survives credit-note generation unchanged and the
 * intercompany copy carries its marker, while ordinary lines are untouched. Same package as
 * the service so the package-private helpers are reachable — the Phase 0 house style.
 */
class InvoiceServiceNoChargeCarryOverTest {

    private static InvoiceItem noChargeSource() {
        InvoiceItem source = new InvoiceItem("u2", "Junior", "Junior consultant — No charge", 0.0, 38.0, 2, "inv",
                InvoiceItemOrigin.BASE);
        source.uuid = "item-1";
        source.noChargeReason = "PILOT_FREE";
        source.listRate = 600.0;
        source.rateReviewDate = LocalDate.of(2026, 10, 31);
        return source;
    }

    @Test
    void creditNoteCopyCarriesTheNoChargeMarkerUnchanged() {
        InvoiceItem source = noChargeSource();

        InvoiceItem cn = InvoiceService.copyItemForCreditNote(source, "cn-1", source.getRate(), source.getHours());

        assertTrue(cn.isNoCharge());
        assertEquals("PILOT_FREE", cn.noChargeReason);
        assertEquals(600.0, cn.listRate, 1e-9);
        assertEquals(LocalDate.of(2026, 10, 31), cn.rateReviewDate);
        assertEquals(0.0, cn.getRate(), 1e-9);
        assertEquals(38.0, cn.getHours(), 1e-9);
        assertEquals("item-1", cn.sourceItemUuid);
    }

    @Test
    void carryOverCopiesOnlyFromNoChargeLines() {
        InvoiceItem target = new InvoiceItem("u2", "Junior", "Junior consultant — No charge", 0.0, 38.0, 2, "internal");
        InvoiceService.copyNoChargeFields(noChargeSource(), target);
        assertTrue(target.isNoCharge());
        assertEquals(600.0, target.listRate, 1e-9);

        InvoiceItem ordinarySource = new InvoiceItem("u1", "Senior", "Advisory", 1450.0, 10.0, 1, "inv");
        InvoiceItem ordinaryTarget = new InvoiceItem("u1", "Senior", "Advisory", 1450.0, 10.0, 1, "cn");
        InvoiceService.copyNoChargeFields(ordinarySource, ordinaryTarget);
        assertNull(ordinaryTarget.noChargeReason);
        assertFalse(ordinaryTarget.isNoCharge());
    }

    @Test
    void carryOverToleratesNulls() {
        InvoiceService.copyNoChargeFields(null, new InvoiceItem());
        InvoiceService.copyNoChargeFields(noChargeSource(), null);
    }
}

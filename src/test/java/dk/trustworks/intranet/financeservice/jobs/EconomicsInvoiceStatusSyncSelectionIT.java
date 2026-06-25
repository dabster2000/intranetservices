package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI-gated (@QuarkusTest, needs DB): proves selectCandidateUuids polls only numbered,
 * still-BOOKED invoices issued within the recency window, and excludes the old BOOKED
 * tail, already-PAID invoices, and unnumbered drafts.
 */
@QuarkusTest
class EconomicsInvoiceStatusSyncSelectionIT {

    private Company seedCompany() {
        Company c = new Company();
        c.setUuid(UUID.randomUUID().toString());
        c.setName("Test Co");
        c.persist();
        return c;
    }

    private Invoice seed(Company c, int invoiceNumber, EconomicsInvoiceStatus status, LocalDate invoicedate) {
        Invoice inv = new Invoice();
        inv.setUuid(UUID.randomUUID().toString());
        inv.setCompany(c);
        inv.setInvoicenumber(invoiceNumber);
        inv.setEconomicsStatus(status);
        inv.setInvoicedate(invoicedate);
        inv.persist();
        return inv;
    }

    @Test
    @TestTransaction
    void selects_recent_booked_numbered_excludes_old_paid_and_drafts() {
        Company c = seedCompany();
        // 365-day window relative to a fixed "today"; cutoff = 2025-06-25
        LocalDate cutoff = EconomicsInvoiceStatusSyncBatchlet.computeCutoff(LocalDate.of(2026, 6, 25), 365);

        Invoice recentBooked = seed(c, 5001, EconomicsInvoiceStatus.BOOKED, LocalDate.of(2026, 6, 1));
        Invoice oldBooked    = seed(c, 5002, EconomicsInvoiceStatus.BOOKED, LocalDate.of(2024, 1, 1));
        Invoice recentPaid   = seed(c, 5003, EconomicsInvoiceStatus.PAID,   LocalDate.of(2026, 6, 1));
        Invoice unnumbered   = seed(c, 0,    EconomicsInvoiceStatus.BOOKED, LocalDate.of(2026, 6, 1));

        List<String> selected = EconomicsInvoiceStatusSyncBatchlet.selectCandidateUuids(cutoff);

        assertTrue(selected.contains(recentBooked.getUuid()), "recent numbered BOOKED is polled");
        assertFalse(selected.contains(oldBooked.getUuid()), "BOOKED older than the window is skipped");
        assertFalse(selected.contains(recentPaid.getUuid()), "already-PAID is not re-polled");
        assertFalse(selected.contains(unnumbered.getUuid()), "unnumbered draft is not polled");
    }
}

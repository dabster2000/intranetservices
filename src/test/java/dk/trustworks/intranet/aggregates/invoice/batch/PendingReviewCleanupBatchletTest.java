package dk.trustworks.intranet.aggregates.invoice.batch;

import dk.trustworks.intranet.aggregates.invoice.economics.notifications.PendingReviewNotificationService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceFinalizationOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceRepository;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PendingReviewCleanupBatchlet}.
 *
 * Uses plain Mockito — no Quarkus container or database needed.
 * SPEC-INV-001 §9.5.
 */
@ExtendWith(MockitoExtension.class)
class PendingReviewCleanupBatchletTest {

    @InjectMocks
    PendingReviewCleanupBatchlet batchlet;

    @Mock
    InvoiceRepository invoices;

    @Mock
    InvoiceFinalizationOrchestrator orchestrator;

    @Mock
    PendingReviewNotificationService notifier;

    // ── Test 1: happy path — both invoices cancelled and notified ─────────────

    /**
     * When two stale PENDING_REVIEW invoices exist, cancelFinalization and
     * notifyAutoReverted must each be called once per invoice.
     */
    @Test
    void cleanup_calls_cancelFinalization_and_notifier_for_each_stale_invoice() {
        Invoice inv1 = pendingReviewInvoice("uuid-1", 101);
        Invoice inv2 = pendingReviewInvoice("uuid-2", 202);

        // Stub: any cutoff date returns two stale invoices
        when(invoices.listPendingReviewOlderThan(any(LocalDate.class)))
                .thenReturn(List.of(inv1, inv2));

        batchlet.run();

        verify(orchestrator).cancelFinalization("uuid-1");
        verify(orchestrator).cancelFinalization("uuid-2");
        verify(notifier).notifyAutoReverted(inv1);
        verify(notifier).notifyAutoReverted(inv2);
    }

    // ── Test 2: one invoice fails — remaining invoices still processed ─────────

    /**
     * When cancelFinalization throws for the first invoice, the batchlet must catch
     * the exception and still process the second invoice.
     */
    @Test
    void cleanup_continues_after_single_invoice_failure() {
        Invoice inv1 = pendingReviewInvoice("uuid-fail", 301);
        Invoice inv2 = pendingReviewInvoice("uuid-ok", 302);

        when(invoices.listPendingReviewOlderThan(any(LocalDate.class)))
                .thenReturn(List.of(inv1, inv2));

        // First invoice throws during cancelFinalization
        doThrow(new RuntimeException("e-conomics draft delete failed"))
                .when(orchestrator).cancelFinalization("uuid-fail");

        batchlet.run();

        // First invoice: cancelFinalization was attempted, notifier was NOT called
        verify(orchestrator).cancelFinalization("uuid-fail");
        verify(notifier, never()).notifyAutoReverted(inv1);

        // Second invoice: processed normally despite first failure
        verify(orchestrator).cancelFinalization("uuid-ok");
        verify(notifier).notifyAutoReverted(inv2);
    }

    // ── Test 3: no stale invoices — no interactions with orchestrator ──────────

    @Test
    void cleanup_does_nothing_when_no_stale_invoices() {
        when(invoices.listPendingReviewOlderThan(any(LocalDate.class)))
                .thenReturn(List.of());

        batchlet.run();

        verifyNoInteractions(orchestrator);
        verifyNoInteractions(notifier);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice pendingReviewInvoice(String uuid, int draftNumber) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(draftNumber);
        inv.setClientname("Test Client");
        inv.setInvoicedate(LocalDate.now().minusDays(10));

        Company company = new Company();
        company.setUuid("co-1");
        inv.setCompany(company);
        return inv;
    }
}

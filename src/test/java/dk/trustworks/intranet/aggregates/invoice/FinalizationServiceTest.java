package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import dk.trustworks.intranet.aggregates.invoice.services.v2.FinalizationService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FinalizationService.
 *
 * Tests invoice finalization from DRAFT to CREATED state.
 */
@QuarkusTest
public class FinalizationServiceTest {

    @Inject
    FinalizationService finalizationService;

    @Inject
    InvoiceRepository repository;

    private final List<String> testInvoiceUuids = new ArrayList<>();

    @BeforeEach
    @Transactional
    public void setUp() {
        // Clean up any existing test data
        testInvoiceUuids.clear();
    }

    @AfterEach
    @Transactional
    public void tearDown() {
        // Clean up test invoices
        for (String uuid : testInvoiceUuids) {
            Invoice invoice = repository.findById(uuid);
            if (invoice != null) {
                repository.delete(invoice);
            }
        }
        testInvoiceUuids.clear();
    }

    @Test
    @Transactional
    public void testFinalizeDraftInvoice() {
        // Create draft invoice
        Invoice draft = createDraftInvoice(InvoiceType.INVOICE);

        // Finalize
        Invoice finalized = finalizationService.finalize(draft.getUuid());

        // Assertions
        assertNotNull(finalized);
        assertEquals(LifecycleStatus.CREATED, finalized.getLifecycleStatus());
        assertEquals(ProcessingState.IDLE, finalized.getProcessingState());
        assertNull(finalized.getQueueReason());
        assertNotNull(finalized.getInvoicenumber());
        assertNotNull(finalized.getInvoicedate());
        assertNotNull(finalized.getDuedate());
        assertNotNull(finalized.getInvoiceYear());
        assertNotNull(finalized.getInvoiceMonth());

        // Verify due date is 30 days from invoice date
        assertEquals(finalized.getInvoicedate().plusDays(30), finalized.getDuedate());

        // Verify year and month match invoice date
        assertEquals(finalized.getInvoicedate().getYear(), finalized.getInvoiceYear());
        assertEquals(finalized.getInvoicedate().getMonthValue(), finalized.getInvoiceMonth());
    }

    @Test
    @Transactional
    public void testFinalizePhantomInvoice() {
        // Create draft PHANTOM invoice
        Invoice draft = createDraftInvoice(InvoiceType.PHANTOM);

        // Finalize
        Invoice finalized = finalizationService.finalize(draft.getUuid());

        // Assertions
        assertNotNull(finalized);
        assertEquals(LifecycleStatus.CREATED, finalized.getLifecycleStatus());
        // PHANTOM invoices should NOT get an invoice number
        assertNull(finalized.getInvoicenumber());
        assertNotNull(finalized.getInvoicedate());
        assertNotNull(finalized.getDuedate());
    }

    @Test
    @Transactional
    public void testCannotFinalizeNonDraftInvoice() {
        // Create invoice in CREATED state
        Invoice created = createDraftInvoice(InvoiceType.INVOICE);
        created.setLifecycleStatus(LifecycleStatus.CREATED);
        repository.persist(created);

        // Try to finalize (should fail)
        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> finalizationService.finalize(created.getUuid())
        );

        assertTrue(exception.getMessage().contains("Cannot finalize invoice"));
        assertTrue(exception.getMessage().contains("expected DRAFT"));
    }

    @Test
    @Transactional
    public void testFinalizeNonExistentInvoice() {
        String nonExistentUuid = UUID.randomUUID().toString();

        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> finalizationService.finalize(nonExistentUuid)
        );

        assertTrue(exception.getMessage().contains("Invoice not found"));
        assertEquals(404, exception.getResponse().getStatus());
    }

    @Test
    @Transactional
    public void testCanFinalize() {
        // Create draft invoice
        Invoice draft = createDraftInvoice(InvoiceType.INVOICE);

        // Should be able to finalize draft
        assertTrue(finalizationService.canFinalize(draft.getUuid()));

        // Finalize it
        finalizationService.finalize(draft.getUuid());

        // Should not be able to finalize again
        assertFalse(finalizationService.canFinalize(draft.getUuid()));
    }

    @Test
    @Transactional
    public void testFinalizeAssignsSequentialNumbers() {
        String companyUuid = UUID.randomUUID().toString();

        // Create two draft invoices for same company
        Invoice draft1 = createDraftInvoice(InvoiceType.INVOICE);
        draft1.setIssuerCompanyuuid(companyUuid);
        draft1.setInvoiceSeries("TEST");
        repository.persist(draft1);

        Invoice draft2 = createDraftInvoice(InvoiceType.INVOICE);
        draft2.setIssuerCompanyuuid(companyUuid);
        draft2.setInvoiceSeries("TEST");
        repository.persist(draft2);

        // Finalize both
        Invoice finalized1 = finalizationService.finalize(draft1.getUuid());
        Invoice finalized2 = finalizationService.finalize(draft2.getUuid());

        // Numbers should be sequential
        assertNotNull(finalized1.getInvoicenumber());
        assertNotNull(finalized2.getInvoicenumber());
        assertEquals(finalized1.getInvoicenumber() + 1, finalized2.getInvoicenumber());
    }

    /**
     * Helper method to create a draft invoice for testing.
     */
    private Invoice createDraftInvoice(InvoiceType type) {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        invoice.setProcessingState(ProcessingState.IDLE);
        invoice.setType(type);
        invoice.setIssuerCompanyuuid(UUID.randomUUID().toString());
        invoice.setDebtorCompanyuuid(UUID.randomUUID().toString());
        invoice.setInvoiceSeries("TEST");

        repository.persist(invoice);
        testInvoiceUuids.add(invoice.getUuid());

        return invoice;
    }
}

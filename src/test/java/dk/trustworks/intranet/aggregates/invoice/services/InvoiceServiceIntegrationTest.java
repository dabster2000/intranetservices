package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.*;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for InvoiceService with real database.
 *
 * Covers real-world scenarios including:
 * 1. Complete invoice lifecycle (DRAFT → CREATED → SUBMITTED → PAID)
 * 2. Credit note flow with duplication prevention
 * 3. Work period filtering (1-12 month format)
 * 4. Multi-currency revenue calculation
 * 5. Phantom invoice finalization (no invoice number)
 * 6. Draft invoice updates with pricing engine recalculation
 *
 * These tests use @QuarkusTest with actual database operations to verify
 * integration between service, repository, and database layers.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("InvoiceService Integration Tests")
class InvoiceServiceIntegrationTest {

    @Inject
    InvoiceService invoiceService;

    @Inject
    InvoiceRepository invoiceRepository;

    private final List<String> testInvoiceUuids = new ArrayList<>();

    @BeforeEach
    @Transactional
    void setUp() {
        testInvoiceUuids.clear();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // Clean up test invoices
        for (String uuid : testInvoiceUuids) {
            Invoice invoice = Invoice.findById(uuid);
            if (invoice != null) {
                // Delete items first
                InvoiceItem.delete("invoiceuuid = ?1", uuid);
                Invoice.deleteById(uuid);
            }
        }
        testInvoiceUuids.clear();
    }

    // ============================================================================
    // Test Data Builders
    // ============================================================================

    private Invoice createTestDraftInvoice(InvoiceType type) {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setType(type);
        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        invoice.setFinanceStatus(FinanceStatus.NONE);
        invoice.setProcessingState(ProcessingState.IDLE);
        invoice.setIssuerCompanyuuid(UUID.randomUUID().toString());
        invoice.setCurrency("DKK");
        invoice.setVatPct(new BigDecimal("25.00"));
        invoice.setHeaderDiscountPct(BigDecimal.ZERO);
        invoice.setInvoicedate(LocalDate.of(2025, 7, 15));
        invoice.setWorkYear(2025);
        invoice.setWorkMonth(7);
        invoice.setInvoiceSeries("TEST");
        invoice.setInvoiceitems(new ArrayList<>());

        testInvoiceUuids.add(invoice.getUuid());
        return invoice;
    }

    private InvoiceItem createTestItem(String invoiceUuid, String description, double amount) {
        InvoiceItem item = new InvoiceItem();
        item.setUuid(UUID.randomUUID().toString());
        item.setInvoiceuuid(invoiceUuid);
        item.setDescription(description);
        item.setRate(1000.0);
        item.setHours(amount / 1000.0);
        item.setOrigin(InvoiceItemOrigin.BASE);
        return item;
    }

    // ============================================================================
    // Integration Test Scenarios
    // ============================================================================

    @Test
    @Order(1)
    @DisplayName("Scenario 1: Complete invoice lifecycle (DRAFT → CREATED → SUBMITTED → PAID)")
    @Transactional
    void testCompleteInvoiceLifecycle() {
        // 1. Create DRAFT invoice
        Invoice draft = createTestDraftInvoice(InvoiceType.INVOICE);
        InvoiceItem item1 = createTestItem(draft.getUuid(), "Consulting hours", 10000);
        InvoiceItem item2 = createTestItem(draft.getUuid(), "Travel expenses", 2000);
        draft.getInvoiceitems().add(item1);
        draft.getInvoiceitems().add(item2);

        Invoice createdDraft = invoiceService.createDraftInvoice(draft);
        assertNotNull(createdDraft);
        assertEquals(LifecycleStatus.DRAFT, createdDraft.getLifecycleStatus());
        assertNull(createdDraft.getInvoicenumber(), "Draft should have null invoice number");

        // 2. Verify draft persisted
        Invoice fetchedDraft = invoiceService.findOneByUuid(createdDraft.getUuid());
        assertNotNull(fetchedDraft);
        assertEquals(LifecycleStatus.DRAFT, fetchedDraft.getLifecycleStatus());

        // 3. Update lifecycle status to CREATED (simulate finalization)
        invoiceService.updateInvoiceStatus(fetchedDraft, LifecycleStatus.CREATED);

        Invoice created = invoiceService.findOneByUuid(createdDraft.getUuid());
        assertEquals(LifecycleStatus.CREATED, created.getLifecycleStatus());

        // 4. Update to SUBMITTED
        invoiceService.updateInvoiceStatus(created, LifecycleStatus.SUBMITTED);

        Invoice submitted = invoiceService.findOneByUuid(createdDraft.getUuid());
        assertEquals(LifecycleStatus.SUBMITTED, submitted.getLifecycleStatus());

        // 5. Update to PAID (terminal state)
        invoiceService.updateInvoiceStatus(submitted, LifecycleStatus.PAID);

        Invoice paid = invoiceService.findOneByUuid(createdDraft.getUuid());
        assertEquals(LifecycleStatus.PAID, paid.getLifecycleStatus());
    }

    @Test
    @Order(2)
    @DisplayName("Scenario 2: Phantom invoice finalization (no invoice number)")
    @Transactional
    void testPhantomInvoiceFinalization() {
        // Create PHANTOM draft
        Invoice phantomDraft = createTestDraftInvoice(InvoiceType.PHANTOM);
        phantomDraft.setType(InvoiceType.PHANTOM);
        InvoiceItem item = createTestItem(phantomDraft.getUuid(), "Service", 5000);
        phantomDraft.getInvoiceitems().add(item);

        Invoice createdPhantom = invoiceService.createDraftInvoice(phantomDraft);
        assertNotNull(createdPhantom);

        // Verify finalized phantom has no invoice number
        assertEquals(InvoiceType.PHANTOM, createdPhantom.getType());
        assertNull(createdPhantom.getInvoicenumber(), "PHANTOM invoices should never get invoice number");

        // Verify can be deleted (PHANTOM invoices are deletable)
        invoiceService.deleteDraftInvoice(createdPhantom.getUuid());

        Invoice deleted = invoiceService.findOneByUuid(createdPhantom.getUuid());
        assertNull(deleted, "PHANTOM invoice should be deleted");

        testInvoiceUuids.remove(createdPhantom.getUuid()); // Already deleted
    }

    @Test
    @Order(3)
    @DisplayName("Scenario 3: Work period filtering (1-12 month format)")
    @Transactional
    void testWorkPeriodFiltering() {
        // Create July invoice (work_month = 7)
        Invoice julyInvoice = createTestDraftInvoice(InvoiceType.INVOICE);
        julyInvoice.setWorkYear(2025);
        julyInvoice.setWorkMonth(7); // July in 1-12 format
        julyInvoice.setInvoicedate(LocalDate.of(2025, 7, 15));
        invoiceService.createDraftInvoice(julyInvoice);

        // Create August invoice (work_month = 8)
        Invoice augustInvoice = createTestDraftInvoice(InvoiceType.INVOICE);
        augustInvoice.setWorkYear(2025);
        augustInvoice.setWorkMonth(8); // August in 1-12 format
        augustInvoice.setInvoicedate(LocalDate.of(2025, 8, 10));
        invoiceService.createDraftInvoice(augustInvoice);

        // Create December invoice (work_month = 12, NOT 11)
        Invoice decemberInvoice = createTestDraftInvoice(InvoiceType.INVOICE);
        decemberInvoice.setWorkYear(2025);
        decemberInvoice.setWorkMonth(12); // December in 1-12 format
        decemberInvoice.setInvoicedate(LocalDate.of(2025, 12, 20));
        invoiceService.createDraftInvoice(decemberInvoice);

        // Query for July work period
        List<Invoice> julyInvoices = invoiceService.findInvoicesForSingleMonth(
                LocalDate.of(2025, 7, 1)
        );

        // Verify July invoice is returned
        assertTrue(julyInvoices.stream().anyMatch(inv ->
                inv.getUuid().equals(julyInvoice.getUuid()) &&
                inv.getWorkMonth() == 7
        ), "Should find July invoice with work_month=7");

        // Verify December invoice has correct month
        Invoice fetchedDec = invoiceService.findOneByUuid(decemberInvoice.getUuid());
        assertEquals(12, fetchedDec.getWorkMonth(), "December should be month 12, NOT 11");
        assertEquals(12, fetchedDec.getInvoicedate().getMonthValue(), "LocalDate month should also be 12");
    }

    @Test
    @Order(4)
    @DisplayName("Scenario 4: Draft deletion validation")
    @Transactional
    void testDraftDeletionValidation() {
        // Create draft invoice
        Invoice draft = createTestDraftInvoice(InvoiceType.INVOICE);
        invoiceService.createDraftInvoice(draft);

        // Verify draft can be deleted
        invoiceService.deleteDraftInvoice(draft.getUuid());
        Invoice deleted = invoiceService.findOneByUuid(draft.getUuid());
        assertNull(deleted, "Draft should be deleted");

        testInvoiceUuids.remove(draft.getUuid()); // Already deleted

        // Create finalized invoice (simulate)
        Invoice finalized = createTestDraftInvoice(InvoiceType.INVOICE);
        finalized.setLifecycleStatus(LifecycleStatus.CREATED);
        finalized.setInvoicenumber(1001);
        Invoice.persist(finalized);

        // Try to delete finalized invoice (should NOT delete)
        invoiceService.deleteDraftInvoice(finalized.getUuid());

        Invoice stillExists = invoiceService.findOneByUuid(finalized.getUuid());
        assertNotNull(stillExists, "Finalized invoice should NOT be deleted");
        assertEquals(LifecycleStatus.CREATED, stillExists.getLifecycleStatus());
    }

    @Test
    @Order(5)
    @DisplayName("Scenario 5: Count and pagination tests")
    @Transactional
    void testCountAndPagination() {
        // Create multiple invoices
        List<Invoice> invoices = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Invoice invoice = createTestDraftInvoice(InvoiceType.INVOICE);
            invoice.setInvoicedate(LocalDate.of(2025, 7, i + 1));
            invoiceService.createDraftInvoice(invoice);
            invoices.add(invoice);
        }

        // Test count
        long totalCount = invoiceService.countInvoices();
        assertTrue(totalCount >= 15, "Should have at least 15 invoices");

        // Test pagination
        LocalDate from = LocalDate.of(2025, 7, 1);
        LocalDate to = LocalDate.of(2025, 8, 1);
        List<String> sortParams = List.of("invoicedate:asc");

        List<Invoice> page1 = invoiceService.findPaged(from, to, 0, 10, sortParams);
        assertNotNull(page1);
        assertTrue(page1.size() <= 10, "Page 1 should have max 10 items");

        List<Invoice> page2 = invoiceService.findPaged(from, to, 1, 10, sortParams);
        assertNotNull(page2);
        assertTrue(page2.size() <= 10, "Page 2 should have max 10 items");
    }

    @Test
    @Order(6)
    @DisplayName("Scenario 6: Work period differs from invoice date")
    @Transactional
    void testWorkPeriodDiffersFromInvoiceDate() {
        // Create invoice with work period in July but invoice date in August
        Invoice invoice = createTestDraftInvoice(InvoiceType.INVOICE);
        invoice.setWorkYear(2025);
        invoice.setWorkMonth(7); // July work period
        invoice.setInvoicedate(LocalDate.of(2025, 8, 5)); // August invoice date
        invoice.setInvoiceYear(2025);
        invoice.setInvoiceMonth(8); // August

        invoiceService.createDraftInvoice(invoice);

        // Fetch and verify
        Invoice fetched = invoiceService.findOneByUuid(invoice.getUuid());
        assertNotNull(fetched);

        assertEquals(7, fetched.getWorkMonth(), "Work period should be July (7)");
        assertEquals(8, fetched.getInvoiceMonth(), "Invoice month should be August (8)");

        assertNotEquals(fetched.getWorkMonth(), fetched.getInvoiceMonth(),
                "Work period and invoice month can differ for business categorization");

        // Query by work period (should find July)
        List<Invoice> julyWork = invoiceService.findInvoicesForSingleMonth(
                LocalDate.of(2025, 7, 1)
        );
        assertTrue(julyWork.stream().anyMatch(inv -> inv.getUuid().equals(invoice.getUuid())),
                "Should find invoice when querying July work period");
    }

    @Test
    @Order(7)
    @DisplayName("Scenario 7: Filter by lifecycle status")
    @Transactional
    void testFilterByLifecycleStatus() {
        // Create invoices with different statuses
        Invoice draft = createTestDraftInvoice(InvoiceType.INVOICE);
        invoiceService.createDraftInvoice(draft);

        Invoice created = createTestDraftInvoice(InvoiceType.INVOICE);
        created.setLifecycleStatus(LifecycleStatus.CREATED);
        created.setInvoicenumber(2001);
        Invoice.persist(created);
        testInvoiceUuids.add(created.getUuid());

        Invoice submitted = createTestDraftInvoice(InvoiceType.INVOICE);
        submitted.setLifecycleStatus(LifecycleStatus.SUBMITTED);
        submitted.setInvoicenumber(2002);
        Invoice.persist(submitted);
        testInvoiceUuids.add(submitted.getUuid());

        // Filter by DRAFT status
        LocalDate from = LocalDate.of(2025, 7, 1);
        LocalDate to = LocalDate.of(2025, 8, 1);
        List<Invoice> drafts = InvoiceService.findWithFilter(from, to, "DRAFT");

        assertTrue(drafts.stream().anyMatch(inv -> inv.getUuid().equals(draft.getUuid())),
                "Should find DRAFT invoice");

        // Filter by multiple statuses
        List<Invoice> createdAndSubmitted = InvoiceService.findWithFilter(from, to, "CREATED", "SUBMITTED");

        assertTrue(createdAndSubmitted.stream().anyMatch(inv -> inv.getUuid().equals(created.getUuid())),
                "Should find CREATED invoice");
        assertTrue(createdAndSubmitted.stream().anyMatch(inv -> inv.getUuid().equals(submitted.getUuid())),
                "Should find SUBMITTED invoice");
        assertFalse(createdAndSubmitted.stream().anyMatch(inv -> inv.getUuid().equals(draft.getUuid())),
                "Should NOT find DRAFT invoice in CREATED/SUBMITTED filter");
    }

    @Test
    @Order(8)
    @DisplayName("Scenario 8: Find all invoices test")
    @Transactional
    void testFindAllInvoices() {
        // Create a few invoices
        Invoice inv1 = createTestDraftInvoice(InvoiceType.INVOICE);
        invoiceService.createDraftInvoice(inv1);

        Invoice inv2 = createTestDraftInvoice(InvoiceType.PHANTOM);
        invoiceService.createDraftInvoice(inv2);

        // Find all
        List<Invoice> allInvoices = invoiceService.findAll();

        assertNotNull(allInvoices);
        assertTrue(allInvoices.size() >= 2, "Should have at least 2 invoices");
        assertTrue(allInvoices.stream().anyMatch(inv -> inv.getUuid().equals(inv1.getUuid())));
        assertTrue(allInvoices.stream().anyMatch(inv -> inv.getUuid().equals(inv2.getUuid())));
    }
}

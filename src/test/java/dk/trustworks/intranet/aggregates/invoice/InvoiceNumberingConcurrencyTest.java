package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import dk.trustworks.intranet.aggregates.invoice.services.v2.FinalizationService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency test for invoice numbering system.
 *
 * Verifies that InvoiceNumberingService produces unique sequential numbers
 * even under concurrent load with race conditions.
 *
 * This test validates Phase 2 Task 2.2 acceptance criteria:
 * - Multiple concurrent threads can finalize invoices simultaneously
 * - All invoices receive unique numbers (no duplicates)
 * - Numbers are sequential with no gaps
 * - Different companies maintain separate sequences
 * - Different series maintain separate sequences
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InvoiceNumberingConcurrencyTest {

    @Inject
    InvoiceRepository repository;

    @Inject
    FinalizationService finalizationService;

    private List<String> testInvoiceUuids = new ArrayList<>();

    @BeforeEach
    @Transactional
    public void setUp() {
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

    /**
     * Test 1: Concurrent finalization produces unique sequential numbers.
     *
     * Creates 15 draft invoices and finalizes them concurrently using 15 threads.
     * Verifies that:
     * - All invoices are finalized successfully
     * - All invoice numbers are unique (no duplicates)
     * - Numbers are sequential with no gaps
     * - No race conditions occurred
     */
    @Test
    @Order(1)
    public void testConcurrentFinalizationProducesUniqueNumbers() throws InterruptedException {
        int invoiceCount = 15;
        String series = "CONCURRENT-TEST";
        String testCompanyUuid = "test-company-" + UUID.randomUUID().toString();

        // Setup: Create draft invoices
        System.out.println("=== Test 1: Creating " + invoiceCount + " draft invoices ===");
        List<String> draftUuids = createDraftInvoices(testCompanyUuid, series, invoiceCount);
        assertEquals(invoiceCount, draftUuids.size(), "Should create all draft invoices");

        // Execute: Finalize all drafts concurrently
        System.out.println("=== Finalizing concurrently with " + invoiceCount + " threads ===");
        ExecutorService executor = Executors.newFixedThreadPool(invoiceCount);
        CountDownLatch startLatch = new CountDownLatch(1);  // All threads wait for this
        CountDownLatch completionLatch = new CountDownLatch(invoiceCount);  // Wait for all to complete

        // Track errors and successes
        Map<String, Exception> errors = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);

        for (String draftUuid : draftUuids) {
            executor.submit(() -> {
                try {
                    // All threads wait at the gate for maximum concurrency
                    startLatch.await();

                    // Execute finalization
                    finalizationService.finalize(draftUuid);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    errors.put(draftUuid, e);
                    System.err.println("Error finalizing " + draftUuid + ": " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously to maximize race condition chances
        System.out.println("=== Releasing all threads simultaneously ===");
        startLatch.countDown();

        // Wait for all threads to complete (30 second timeout)
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify: Check completion and errors
        assertTrue(completed, "All threads should complete within 30 seconds");
        assertTrue(errors.isEmpty(), "No errors should occur during finalization. Errors: " + errors);
        assertEquals(invoiceCount, successCount.get(), "All invoices should finalize successfully");

        System.out.println("=== All threads completed successfully ===");

        // Verify: Fetch finalized invoices
        List<Invoice> finalized = repository.find(
            "issuerCompanyuuid = ?1 AND invoiceSeries = ?2 AND lifecycleStatus = ?3",
            testCompanyUuid,
            series,
            LifecycleStatus.CREATED
        ).list();

        assertEquals(invoiceCount, finalized.size(),
            "All " + invoiceCount + " invoices should be finalized");

        // Verify: Check for unique numbers
        Set<Integer> numbers = new HashSet<>();
        Map<Integer, String> numberToUuid = new HashMap<>();

        for (Invoice invoice : finalized) {
            assertNotNull(invoice.getInvoicenumber(),
                "Invoice " + invoice.getUuid() + " should have a number");
            assertTrue(invoice.getInvoicenumber() > 0,
                "Invoice number should be positive: " + invoice.getInvoicenumber());

            // Check for duplicates
            if (numbers.contains(invoice.getInvoicenumber())) {
                String duplicateUuid = numberToUuid.get(invoice.getInvoicenumber());
                fail(String.format(
                    "DUPLICATE INVOICE NUMBER DETECTED: %d is assigned to both %s and %s",
                    invoice.getInvoicenumber(),
                    duplicateUuid,
                    invoice.getUuid()
                ));
            }

            numbers.add(invoice.getInvoicenumber());
            numberToUuid.put(invoice.getInvoicenumber(), invoice.getUuid());
        }

        System.out.println("=== No duplicate numbers found ===");
        System.out.println("Invoice numbers assigned: " + numbers.stream()
            .sorted()
            .map(String::valueOf)
            .collect(Collectors.joining(", ")));

        // Verify: Check sequential (no gaps)
        List<Integer> sortedNumbers = new ArrayList<>(numbers);
        Collections.sort(sortedNumbers);

        for (int i = 1; i < sortedNumbers.size(); i++) {
            int current = sortedNumbers.get(i);
            int previous = sortedNumbers.get(i - 1);
            int diff = current - previous;

            assertEquals(1, diff,
                String.format("GAP DETECTED: Numbers jump from %d to %d (diff=%d)",
                    previous, current, diff));
        }

        System.out.println("=== Numbers are sequential with no gaps ===");
        System.out.println("Test 1 PASSED: Concurrent finalization is race-free");
    }

    /**
     * Test 2: Concurrent finalization for different companies.
     *
     * Creates invoices for two different companies and finalizes them concurrently.
     * Verifies that:
     * - Each company gets its own sequential number sequence
     * - Company A's numbers don't interfere with Company B's numbers
     * - Both sequences are independent and collision-free
     */
    @Test
    @Order(2)
    public void testConcurrentFinalizationDifferentCompanies() throws InterruptedException {
        int invoicesPerCompany = 10;
        String series = "MULTI-COMPANY";
        String testCompanyUuid = "test-company-" + UUID.randomUUID().toString();
        String testCompanyUuid2 = "test-company2-" + UUID.randomUUID().toString();

        System.out.println("=== Test 2: Creating invoices for 2 different companies ===");

        // Create drafts for both companies
        List<String> company1Drafts = createDraftInvoices(testCompanyUuid, series, invoicesPerCompany);
        List<String> company2Drafts = createDraftInvoices(testCompanyUuid2, series, invoicesPerCompany);

        assertEquals(invoicesPerCompany, company1Drafts.size());
        assertEquals(invoicesPerCompany, company2Drafts.size());

        // Combine all drafts and finalize concurrently
        List<String> allDrafts = new ArrayList<>();
        allDrafts.addAll(company1Drafts);
        allDrafts.addAll(company2Drafts);

        System.out.println("=== Finalizing 20 invoices concurrently (2 companies) ===");
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(allDrafts.size());
        Map<String, Exception> errors = new ConcurrentHashMap<>();

        for (String draftUuid : allDrafts) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    finalizationService.finalize(draftUuid);
                } catch (Exception e) {
                    errors.put(draftUuid, e);
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);

        // Verify: Check each company's sequence independently
        List<Invoice> company1Invoices = repository.find(
            "issuerCompanyuuid = ?1 AND invoiceSeries = ?2 AND lifecycleStatus = ?3",
            testCompanyUuid, series, LifecycleStatus.CREATED
        ).list();

        List<Invoice> company2Invoices = repository.find(
            "issuerCompanyuuid = ?1 AND invoiceSeries = ?2 AND lifecycleStatus = ?3",
            testCompanyUuid2, series, LifecycleStatus.CREATED
        ).list();

        assertEquals(invoicesPerCompany, company1Invoices.size(), "Company 1 should have all invoices");
        assertEquals(invoicesPerCompany, company2Invoices.size(), "Company 2 should have all invoices");

        // Verify each company has unique, sequential numbers
        verifyUniqueAndSequential(company1Invoices, "Company 1");
        verifyUniqueAndSequential(company2Invoices, "Company 2");

        System.out.println("Test 2 PASSED: Different companies maintain separate sequences");
    }

    /**
     * Test 3: Concurrent finalization with different series.
     *
     * Creates invoices with different series (EXPORT, INTERNAL) for the same company
     * and finalizes them concurrently. Verifies that:
     * - Each series maintains its own sequential number sequence
     * - Series don't interfere with each other
     * - Both sequences are collision-free
     */
    @Test
    @Order(3)
    public void testConcurrentFinalizationWithDifferentSeries() throws InterruptedException {
        int invoicesPerSeries = 10;
        String seriesA = "EXPORT";
        String seriesB = "INTERNAL";
        String testCompanyUuid = "test-company-" + UUID.randomUUID().toString();

        System.out.println("=== Test 3: Creating invoices with 2 different series ===");

        // Create drafts for both series
        List<String> seriesADrafts = createDraftInvoices(testCompanyUuid, seriesA, invoicesPerSeries);
        List<String> seriesBDrafts = createDraftInvoices(testCompanyUuid, seriesB, invoicesPerSeries);

        assertEquals(invoicesPerSeries, seriesADrafts.size());
        assertEquals(invoicesPerSeries, seriesBDrafts.size());

        // Combine and finalize concurrently
        List<String> allDrafts = new ArrayList<>();
        allDrafts.addAll(seriesADrafts);
        allDrafts.addAll(seriesBDrafts);

        System.out.println("=== Finalizing 20 invoices concurrently (2 series) ===");
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(allDrafts.size());
        Map<String, Exception> errors = new ConcurrentHashMap<>();

        for (String draftUuid : allDrafts) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    finalizationService.finalize(draftUuid);
                } catch (Exception e) {
                    errors.put(draftUuid, e);
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);

        // Verify: Check each series independently
        List<Invoice> seriesAInvoices = repository.find(
            "issuerCompanyuuid = ?1 AND invoiceSeries = ?2 AND lifecycleStatus = ?3",
            testCompanyUuid, seriesA, LifecycleStatus.CREATED
        ).list();

        List<Invoice> seriesBInvoices = repository.find(
            "issuerCompanyuuid = ?1 AND invoiceSeries = ?2 AND lifecycleStatus = ?3",
            testCompanyUuid, seriesB, LifecycleStatus.CREATED
        ).list();

        assertEquals(invoicesPerSeries, seriesAInvoices.size(), "Series A should have all invoices");
        assertEquals(invoicesPerSeries, seriesBInvoices.size(), "Series B should have all invoices");

        // Verify each series has unique, sequential numbers
        verifyUniqueAndSequential(seriesAInvoices, "Series " + seriesA);
        verifyUniqueAndSequential(seriesBInvoices, "Series " + seriesB);

        System.out.println("Test 3 PASSED: Different series maintain separate sequences");
    }

    /**
     * Test 4: High-load concurrency test with 20 threads.
     *
     * Stress test with maximum concurrency to expose any race conditions.
     */
    @Test
    @Order(4)
    public void testHighLoadConcurrency() throws InterruptedException {
        int invoiceCount = 20;
        String series = "HIGH-LOAD";
        String testCompanyUuid = "test-company-" + UUID.randomUUID().toString();

        System.out.println("=== Test 4: High-load test with 20 concurrent threads ===");

        List<String> draftUuids = createDraftInvoices(testCompanyUuid, series, invoiceCount);
        assertEquals(invoiceCount, draftUuids.size());

        ExecutorService executor = Executors.newFixedThreadPool(invoiceCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(invoiceCount);
        Map<String, Exception> errors = new ConcurrentHashMap<>();

        for (String draftUuid : draftUuids) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    finalizationService.finalize(draftUuid);
                } catch (Exception e) {
                    errors.put(draftUuid, e);
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);

        List<Invoice> finalized = repository.find(
            "issuerCompanyuuid = ?1 AND invoiceSeries = ?2 AND lifecycleStatus = ?3",
            testCompanyUuid, series, LifecycleStatus.CREATED
        ).list();

        assertEquals(invoiceCount, finalized.size());
        verifyUniqueAndSequential(finalized, "High-load test");

        System.out.println("Test 4 PASSED: High-load concurrency handled correctly");
    }

    // ========== Helper Methods ==========

    /**
     * Creates multiple draft invoices for testing.
     *
     * @param companyUuid Company UUID
     * @param series Invoice series
     * @param count Number of invoices to create
     * @return List of created invoice UUIDs
     */
    @Transactional
    protected List<String> createDraftInvoices(String companyUuid, String series, int count) {
        List<String> uuids = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Invoice draft = new Invoice();
            draft.setUuid(UUID.randomUUID().toString());
            draft.setIssuerCompanyuuid(companyUuid);
            draft.setType(InvoiceType.INVOICE);
            draft.setLifecycleStatus(LifecycleStatus.DRAFT);
            draft.setFinanceStatus(FinanceStatus.NONE);
            draft.setProcessingState(ProcessingState.IDLE);
            draft.setInvoiceSeries(series);
            draft.setInvoicedate(LocalDate.now());
            draft.setDuedate(LocalDate.now().plusDays(30));
            draft.setCurrency("DKK");
            draft.setVatPct(new BigDecimal("25.00"));
            draft.setHeaderDiscountPct(BigDecimal.ZERO);
            draft.setBillToName("Test Customer " + i);
            draft.setBillToLine1("Test Address " + i);
            draft.setBillToCity("Copenhagen");
            draft.setBillToCountry("DK");

            repository.persist(draft);
            uuids.add(draft.getUuid());
            testInvoiceUuids.add(draft.getUuid());
        }

        return uuids;
    }

    /**
     * Verifies that a list of invoices has unique, sequential invoice numbers.
     *
     * @param invoices List of invoices to verify
     * @param label Label for logging
     */
    private void verifyUniqueAndSequential(List<Invoice> invoices, String label) {
        Set<Integer> numbers = new HashSet<>();

        // Check for unique numbers
        for (Invoice invoice : invoices) {
            assertNotNull(invoice.getInvoicenumber(),
                label + ": Invoice " + invoice.getUuid() + " should have a number");

            boolean added = numbers.add(invoice.getInvoicenumber());
            assertTrue(added,
                label + ": Duplicate invoice number detected: " + invoice.getInvoicenumber());
        }

        // Check for sequential (no gaps)
        List<Integer> sortedNumbers = new ArrayList<>(numbers);
        Collections.sort(sortedNumbers);

        for (int i = 1; i < sortedNumbers.size(); i++) {
            int current = sortedNumbers.get(i);
            int previous = sortedNumbers.get(i - 1);
            int diff = current - previous;

            assertEquals(1, diff,
                String.format("%s: Gap detected - numbers jump from %d to %d (diff=%d)",
                    label, previous, current, diff));
        }

        System.out.println(label + ": All numbers unique and sequential (" +
            sortedNumbers.get(0) + " to " + sortedNumbers.get(sortedNumbers.size() - 1) + ")");
    }
}

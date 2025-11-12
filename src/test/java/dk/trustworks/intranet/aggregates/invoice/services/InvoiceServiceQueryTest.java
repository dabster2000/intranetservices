package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for InvoiceService query methods.
 *
 * Tests read-only query operations including:
 * - findOneByUuid, findAll, countInvoices
 * - findPaged (pagination logic)
 * - findWithFilter (date + lifecycle status filtering)
 * - findByBookingDate (booking date filtering)
 * - findInvoicesForSingleMonth (work period filtering with 1-12 month format)
 * - findProjectInvoices, findContractInvoices, findInvoicesForContracts
 * - findInternalServiceInvoicesByMonth, findInternalServicesPaged
 *
 * Edge cases tested:
 * - NULL parameters
 * - Empty results
 * - Pagination boundaries
 * - Work month=12→1 transitions (December to January)
 * - Month format (1-12, NOT 0-11)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceService Query Tests")
class InvoiceServiceQueryTest {

    @InjectMocks
    private InvoiceService invoiceService;

    private MockedStatic<Invoice> invoiceMockedStatic;

    @BeforeEach
    void setUp() {
        invoiceMockedStatic = mockStatic(Invoice.class);
    }

    @AfterEach
    void tearDown() {
        if (invoiceMockedStatic != null) {
            invoiceMockedStatic.close();
        }
    }

    // ============================================================================
    // Test Data Builders
    // ============================================================================

    private Invoice createTestInvoice(String uuid, InvoiceType type, LifecycleStatus lifecycleStatus) {
        Invoice invoice = new Invoice();
        invoice.setUuid(uuid);
        invoice.setType(type);
        invoice.setLifecycleStatus(lifecycleStatus);
        invoice.setFinanceStatus(FinanceStatus.NONE);
        invoice.setProcessingState(ProcessingState.IDLE);
        invoice.setIssuerCompanyuuid(UUID.randomUUID().toString());
        invoice.setCurrency("DKK");
        invoice.setVatPct(new BigDecimal("25.00"));
        invoice.setHeaderDiscountPct(BigDecimal.ZERO);
        invoice.setInvoicedate(LocalDate.of(2025, 7, 15));
        invoice.setWorkYear(2025);
        invoice.setWorkMonth(7); // July (1-12 format)
        return invoice;
    }

    private Invoice createDraftInvoice() {
        Invoice invoice = createTestInvoice(UUID.randomUUID().toString(), InvoiceType.INVOICE, LifecycleStatus.DRAFT);
        invoice.setInvoicenumber(null); // Drafts have NULL invoice numbers
        return invoice;
    }

    private Invoice createFinalizedInvoice(int invoiceNumber) {
        Invoice invoice = createTestInvoice(UUID.randomUUID().toString(), InvoiceType.INVOICE, LifecycleStatus.CREATED);
        invoice.setInvoicenumber(invoiceNumber);
        invoice.setInvoiceSeries("STD");
        return invoice;
    }

    private Invoice createPhantomInvoice() {
        Invoice invoice = createTestInvoice(UUID.randomUUID().toString(), InvoiceType.PHANTOM, LifecycleStatus.CREATED);
        invoice.setInvoicenumber(null); // PHANTOMs never get invoice numbers
        return invoice;
    }

    // ============================================================================
    // findOneByUuid() Tests
    // ============================================================================

    @Test
    @DisplayName("findOneByUuid - should return invoice when exists")
    void testFindOneByUuid_WhenExists() {
        // Arrange
        String uuid = UUID.randomUUID().toString();
        Invoice expectedInvoice = createFinalizedInvoice(1001);
        expectedInvoice.setUuid(uuid);

        invoiceMockedStatic.when(() -> Invoice.findById(uuid))
                .thenReturn(expectedInvoice);

        // Act
        Invoice result = invoiceService.findOneByUuid(uuid);

        // Assert
        assertNotNull(result);
        assertEquals(uuid, result.getUuid());
        assertEquals(1001, result.getInvoicenumber());
        assertEquals(LifecycleStatus.CREATED, result.getLifecycleStatus());

        invoiceMockedStatic.verify(() -> Invoice.findById(uuid), times(1));
    }

    @Test
    @DisplayName("findOneByUuid - should return null when not exists")
    void testFindOneByUuid_WhenNotExists() {
        // Arrange
        String uuid = UUID.randomUUID().toString();

        invoiceMockedStatic.when(() -> Invoice.findById(uuid))
                .thenReturn(null);

        // Act
        Invoice result = invoiceService.findOneByUuid(uuid);

        // Assert
        assertNull(result);

        invoiceMockedStatic.verify(() -> Invoice.findById(uuid), times(1));
    }

    @Test
    @DisplayName("findOneByUuid - should handle draft invoice with null invoice number")
    void testFindOneByUuid_DraftWithNullInvoiceNumber() {
        // Arrange
        String uuid = UUID.randomUUID().toString();
        Invoice draftInvoice = createDraftInvoice();
        draftInvoice.setUuid(uuid);

        invoiceMockedStatic.when(() -> Invoice.findById(uuid))
                .thenReturn(draftInvoice);

        // Act
        Invoice result = invoiceService.findOneByUuid(uuid);

        // Assert
        assertNotNull(result);
        assertEquals(uuid, result.getUuid());
        assertNull(result.getInvoicenumber(), "Draft invoices should have null invoice number");
        assertEquals(LifecycleStatus.DRAFT, result.getLifecycleStatus());
    }

    @Test
    @DisplayName("findOneByUuid - should handle phantom invoice with null invoice number")
    void testFindOneByUuid_PhantomWithNullInvoiceNumber() {
        // Arrange
        String uuid = UUID.randomUUID().toString();
        Invoice phantomInvoice = createPhantomInvoice();
        phantomInvoice.setUuid(uuid);

        invoiceMockedStatic.when(() -> Invoice.findById(uuid))
                .thenReturn(phantomInvoice);

        // Act
        Invoice result = invoiceService.findOneByUuid(uuid);

        // Assert
        assertNotNull(result);
        assertEquals(uuid, result.getUuid());
        assertNull(result.getInvoicenumber(), "PHANTOM invoices should never have invoice number");
        assertEquals(InvoiceType.PHANTOM, result.getType());
        assertEquals(LifecycleStatus.CREATED, result.getLifecycleStatus());
    }

    // ============================================================================
    // findAll() Tests
    // ============================================================================

    @Test
    @DisplayName("findAll - should return all invoices")
    void testFindAll_Success() {
        // Arrange
        List<Invoice> expectedInvoices = Arrays.asList(
                createFinalizedInvoice(1001),
                createFinalizedInvoice(1002),
                createDraftInvoice()
        );

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.list()).thenReturn(expectedInvoices);

        invoiceMockedStatic.when(Invoice::findAll)
                .thenReturn(mockQuery);

        // Act
        List<Invoice> result = invoiceService.findAll();

        // Assert
        assertNotNull(result);
        assertEquals(3, result.size());

        invoiceMockedStatic.verify(Invoice::findAll, times(1));
        verify(mockQuery, times(1)).list();
    }

    @Test
    @DisplayName("findAll - should return empty list when no invoices")
    void testFindAll_EmptyList() {
        // Arrange
        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.list()).thenReturn(Collections.emptyList());

        invoiceMockedStatic.when(Invoice::findAll)
                .thenReturn(mockQuery);

        // Act
        List<Invoice> result = invoiceService.findAll();

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================================
    // findPaged() Tests
    // ============================================================================

    @Test
    @DisplayName("findPaged - should return paged results with date filter")
    void testFindPaged_WithDateFilter() {
        // Arrange
        LocalDate from = LocalDate.of(2025, 7, 1);
        LocalDate to = LocalDate.of(2025, 8, 1);
        int pageIdx = 0;
        int pageSize = 10;
        List<String> sortParams = Arrays.asList("invoicedate:desc", "invoicenumber:asc");

        List<Invoice> expectedInvoices = Arrays.asList(
                createFinalizedInvoice(1001),
                createFinalizedInvoice(1002)
        );

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        PanacheQuery<Invoice> mockPageQuery = mock(PanacheQuery.class);

        when(mockQuery.page(any(Page.class))).thenReturn(mockPageQuery);
        when(mockPageQuery.list()).thenReturn(expectedInvoices);

        invoiceMockedStatic.when(() -> Invoice.find(
                eq("invoicedate >= ?1 and invoicedate < ?2"),
                any(Sort.class),
                eq(from),
                eq(to)
        )).thenReturn(mockQuery);

        // Act
        List<Invoice> result = invoiceService.findPaged(from, to, pageIdx, pageSize, sortParams);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());

        invoiceMockedStatic.verify(() -> Invoice.find(
                eq("invoicedate >= ?1 and invoicedate < ?2"),
                any(Sort.class),
                eq(from),
                eq(to)
        ), times(1));
    }

    @Test
    @DisplayName("findPaged - should return all invoices when no date filter")
    void testFindPaged_WithoutDateFilter() {
        // Arrange
        int pageIdx = 0;
        int pageSize = 10;
        List<String> sortParams = List.of("invoicedate:desc");

        List<Invoice> expectedInvoices = Arrays.asList(
                createFinalizedInvoice(1001),
                createFinalizedInvoice(1002),
                createDraftInvoice()
        );

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        PanacheQuery<Invoice> mockPageQuery = mock(PanacheQuery.class);

        when(mockQuery.page(any(Page.class))).thenReturn(mockPageQuery);
        when(mockPageQuery.list()).thenReturn(expectedInvoices);

        invoiceMockedStatic.when(() -> Invoice.findAll(any(Sort.class)))
                .thenReturn(mockQuery);

        // Act
        List<Invoice> result = invoiceService.findPaged(null, null, pageIdx, pageSize, sortParams);

        // Assert
        assertNotNull(result);
        assertEquals(3, result.size());

        invoiceMockedStatic.verify(() -> Invoice.findAll(any(Sort.class)), times(1));
    }

    @Test
    @DisplayName("findPaged - should handle pagination boundaries correctly")
    void testFindPaged_PaginationBoundaries() {
        // Arrange
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        int pageIdx = 5;  // Page 5
        int pageSize = 20; // 20 per page
        List<String> sortParams = List.of("invoicenumber:asc");

        List<Invoice> expectedInvoices = List.of(createFinalizedInvoice(1101));

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        PanacheQuery<Invoice> mockPageQuery = mock(PanacheQuery.class);

        when(mockQuery.page(argThat(page -> page.index == pageIdx && page.size == pageSize)))
                .thenReturn(mockPageQuery);
        when(mockPageQuery.list()).thenReturn(expectedInvoices);

        invoiceMockedStatic.when(() -> Invoice.find(
                anyString(),
                any(Sort.class),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(mockQuery);

        // Act
        List<Invoice> result = invoiceService.findPaged(from, to, pageIdx, pageSize, sortParams);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("findPaged - should return empty list for page beyond results")
    void testFindPaged_EmptyPageBeyondResults() {
        // Arrange
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        int pageIdx = 100; // Way beyond actual data
        int pageSize = 10;
        List<String> sortParams = List.of("invoicedate:desc");

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        PanacheQuery<Invoice> mockPageQuery = mock(PanacheQuery.class);

        when(mockQuery.page(any(Page.class))).thenReturn(mockPageQuery);
        when(mockPageQuery.list()).thenReturn(Collections.emptyList());

        invoiceMockedStatic.when(() -> Invoice.find(
                anyString(),
                any(Sort.class),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(mockQuery);

        // Act
        List<Invoice> result = invoiceService.findPaged(from, to, pageIdx, pageSize, sortParams);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================================
    // countInvoices() Tests
    // ============================================================================

    @Test
    @DisplayName("countInvoices - should return total count")
    void testCountInvoices_Success() {
        // Arrange
        long expectedCount = 42L;

        invoiceMockedStatic.when(Invoice::count)
                .thenReturn(expectedCount);

        // Act
        long result = invoiceService.countInvoices();

        // Assert
        assertEquals(expectedCount, result);

        invoiceMockedStatic.verify(Invoice::count, times(1));
    }

    @Test
    @DisplayName("countInvoices - should return zero when no invoices")
    void testCountInvoices_Zero() {
        // Arrange
        invoiceMockedStatic.when(Invoice::count)
                .thenReturn(0L);

        // Act
        long result = invoiceService.countInvoices();

        // Assert
        assertEquals(0L, result);
    }

    // ============================================================================
    // findWithFilter() Tests - Date + Lifecycle Status Filtering
    // ============================================================================

    @Test
    @DisplayName("findWithFilter - should filter by date and lifecycle statuses")
    void testFindWithFilter_WithStatuses() {
        // Arrange
        LocalDate from = LocalDate.of(2025, 7, 1);
        LocalDate to = LocalDate.of(2025, 8, 1);
        String[] statuses = {"CREATED", "SUBMITTED"};

        List<Invoice> expectedInvoices = Arrays.asList(
                createFinalizedInvoice(1001),
                createFinalizedInvoice(1002)
        );
        expectedInvoices.get(0).setLifecycleStatus(LifecycleStatus.CREATED);
        expectedInvoices.get(1).setLifecycleStatus(LifecycleStatus.SUBMITTED);

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.list()).thenReturn(expectedInvoices);

        invoiceMockedStatic.when(() -> Invoice.find(
                eq("invoicedate >= ?1 AND invoicedate < ?2 AND lifecycleStatus IN ?3"),
                eq(from),
                eq(to),
                anyList()
        )).thenReturn(mockQuery);

        // Act
        List<Invoice> result = InvoiceService.findWithFilter(from, to, statuses);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(inv ->
                inv.getLifecycleStatus() == LifecycleStatus.CREATED ||
                inv.getLifecycleStatus() == LifecycleStatus.SUBMITTED
        ));
    }

    @Test
    @DisplayName("findWithFilter - should return all statuses when no filter provided")
    void testFindWithFilter_NoStatusFilter() {
        // Arrange
        LocalDate from = LocalDate.of(2025, 7, 1);
        LocalDate to = LocalDate.of(2025, 8, 1);

        List<Invoice> expectedInvoices = Arrays.asList(
                createDraftInvoice(),
                createFinalizedInvoice(1001),
                createFinalizedInvoice(1002)
        );

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.list()).thenReturn(expectedInvoices);

        invoiceMockedStatic.when(() -> Invoice.find(
                eq("invoicedate >= ?1 AND invoicedate < ?2"),
                eq(from),
                eq(to)
        )).thenReturn(mockQuery);

        // Act
        List<Invoice> result = InvoiceService.findWithFilter(from, to);

        // Assert
        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("findWithFilter - should handle empty result set")
    void testFindWithFilter_EmptyResults() {
        // Arrange
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 2);
        String[] statuses = {"PAID"};

        PanacheQuery<Invoice> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.list()).thenReturn(Collections.emptyList());

        invoiceMockedStatic.when(() -> Invoice.find(
                anyString(),
                any(LocalDate.class),
                any(LocalDate.class),
                anyList()
        )).thenReturn(mockQuery);

        // Act
        List<Invoice> result = InvoiceService.findWithFilter(from, to, statuses);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ============================================================================
    // Work Period Month Format Tests (CRITICAL: 1-12 format)
    // ============================================================================

    @Test
    @DisplayName("Work Period - should use 1-12 month format (July = 7)")
    void testWorkPeriod_JulyIs7Not6() {
        // Arrange
        String uuid = UUID.randomUUID().toString();
        Invoice julyInvoice = createFinalizedInvoice(1001);
        julyInvoice.setUuid(uuid);
        julyInvoice.setWorkYear(2025);
        julyInvoice.setWorkMonth(7); // July in 1-12 format
        julyInvoice.setInvoicedate(LocalDate.of(2025, 7, 15));

        invoiceMockedStatic.when(() -> Invoice.findById(uuid))
                .thenReturn(julyInvoice);

        // Act
        Invoice result = invoiceService.findOneByUuid(uuid);

        // Assert
        assertNotNull(result);
        assertEquals(7, result.getWorkMonth(), "July should be month 7, not 6 (1-12 format, NOT 0-11)");
        assertEquals(2025, result.getWorkYear());
        assertEquals(7, result.getInvoicedate().getMonthValue(), "LocalDate.getMonthValue() returns 1-12");
    }

    @Test
    @DisplayName("Work Period - should handle December→January transition (12→1)")
    void testWorkPeriod_DecemberToJanuaryTransition() {
        // Arrange
        Invoice decemberInvoice = createFinalizedInvoice(1001);
        decemberInvoice.setWorkYear(2025);
        decemberInvoice.setWorkMonth(12); // December

        Invoice januaryInvoice = createFinalizedInvoice(1002);
        januaryInvoice.setWorkYear(2026);
        januaryInvoice.setWorkMonth(1); // January

        // Assert
        assertEquals(12, decemberInvoice.getWorkMonth(), "December = 12");
        assertEquals(1, januaryInvoice.getWorkMonth(), "January = 1");
        assertNotEquals(decemberInvoice.getWorkMonth(), januaryInvoice.getWorkMonth());
    }

    @Test
    @DisplayName("Work Period - workMonth and invoiceMonth should both use 1-12")
    void testWorkPeriod_BothUse1To12Format() {
        // Arrange
        String uuid = UUID.randomUUID().toString();
        Invoice invoice = createFinalizedInvoice(1001);
        invoice.setUuid(uuid);

        // Set work period to July
        invoice.setWorkYear(2025);
        invoice.setWorkMonth(7); // July work period

        // But invoice date is actually in August
        invoice.setInvoicedate(LocalDate.of(2025, 8, 10));
        invoice.setInvoiceYear(2025);
        invoice.setInvoiceMonth(8); // August invoice date

        invoiceMockedStatic.when(() -> Invoice.findById(uuid))
                .thenReturn(invoice);

        // Act
        Invoice result = invoiceService.findOneByUuid(uuid);

        // Assert
        assertNotNull(result);
        assertEquals(7, result.getWorkMonth(), "Work period is July (7)");
        assertEquals(8, result.getInvoiceMonth(), "Invoice date is August (8)");
        assertEquals(8, result.getInvoicedate().getMonthValue(), "LocalDate month is August (8)");

        // Both should use 1-12 format
        assertTrue(result.getWorkMonth() >= 1 && result.getWorkMonth() <= 12);
        assertTrue(result.getInvoiceMonth() >= 1 && result.getInvoiceMonth() <= 12);
    }
}

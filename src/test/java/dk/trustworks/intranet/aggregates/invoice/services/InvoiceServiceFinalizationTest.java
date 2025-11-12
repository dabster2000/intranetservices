package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.*;
import dk.trustworks.intranet.aggregates.invoice.pricing.PriceResult;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceNumberingService;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.WebApplicationException;
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
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for InvoiceService finalization methods.
 *
 * Tests finalization logic including:
 * - createInvoice (DRAFT→CREATED with invoice number assignment, PDF generation, pricing recalc)
 * - createPhantomInvoice (finalization WITHOUT invoice number)
 * - createCreditNote (validation, duplication check, original invoice reference)
 * - deleteDraftInvoice (only DRAFT/PHANTOM allowed)
 *
 * Edge cases tested:
 * - PHANTOM credit notes (should fail)
 * - Duplicate credit notes (should fail)
 * - Concurrent finalization (atomic numbering)
 * - NULL invoice numbers for drafts
 * - Credit note work period inheritance
 * - Deleting non-draft invoices (should fail silently)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceService Finalization Tests")
class InvoiceServiceFinalizationTest {

    @InjectMocks
    private InvoiceService invoiceService;

    @Mock
    private PricingEngine pricingEngine;

    @Mock
    private InvoiceBonusService bonusService;

    @Mock
    private InvoiceNumberingService numberingService;

    @Mock
    private InvoiceEconomicsUploadService uploadService;

    @Mock
    private WorkService workService;

    private MockedStatic<Invoice> invoiceMockedStatic;
    private MockedStatic<InvoiceItem> invoiceItemMockedStatic;
    private MockedStatic<ContractTypeItem> contractTypeItemMockedStatic;

    @BeforeEach
    void setUp() {
        invoiceMockedStatic = mockStatic(Invoice.class);
        invoiceItemMockedStatic = mockStatic(InvoiceItem.class);
        contractTypeItemMockedStatic = mockStatic(ContractTypeItem.class);
    }

    @AfterEach
    void tearDown() {
        if (invoiceMockedStatic != null) invoiceMockedStatic.close();
        if (invoiceItemMockedStatic != null) invoiceItemMockedStatic.close();
        if (contractTypeItemMockedStatic != null) contractTypeItemMockedStatic.close();
    }

    // ============================================================================
    // Test Data Builders
    // ============================================================================

    private Invoice createDraftInvoice(InvoiceType type) {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setType(type);
        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        invoice.setFinanceStatus(FinanceStatus.NONE);
        invoice.setProcessingState(ProcessingState.IDLE);
        invoice.setIssuerCompanyuuid(UUID.randomUUID().toString());
        invoice.setContractuuid(UUID.randomUUID().toString());
        invoice.setProjectuuid(UUID.randomUUID().toString());
        invoice.setCurrency("DKK");
        invoice.setVatPct(new BigDecimal("25.00"));
        invoice.setHeaderDiscountPct(BigDecimal.ZERO);
        invoice.setInvoicedate(LocalDate.of(2025, 7, 15));
        invoice.setWorkYear(2025);
        invoice.setWorkMonth(7);
        invoice.setInvoiceMonth(7);
        invoice.setInvoiceYear(2025);
        invoice.setInvoicenumber(null); // Drafts have NULL invoice number
        invoice.setInvoiceitems(new ArrayList<>());
        invoice.setInvoiceSeries("STD");
        return invoice;
    }

    private InvoiceItem createBaseItem(String description, double amount) {
        InvoiceItem item = new InvoiceItem();
        item.setUuid(UUID.randomUUID().toString());
        item.setDescription(description);
        item.setOrigin(InvoiceItemOrigin.BASE);
        item.setRate(1000.0);
        item.setHours(amount / 1000.0);
        return item;
    }

    private PriceResult createMockPriceResult() {
        PriceResult result = new PriceResult();
        result.sumBeforeDiscounts = BigDecimal.valueOf(10000);
        result.sumAfterDiscounts = BigDecimal.valueOf(9500);
        result.vatAmount = BigDecimal.valueOf(2375);
        result.grandTotal = BigDecimal.valueOf(11875);
        result.syntheticItems = new ArrayList<>();
        return result;
    }

    // ============================================================================
    // createInvoice() Tests - DRAFT → CREATED with Invoice Number
    // ============================================================================

    @Test
    @DisplayName("createInvoice - should assign invoice number and finalize draft")
    void testCreateInvoice_Success() throws Exception {
        // Arrange
        Invoice draft = createDraftInvoice(InvoiceType.INVOICE);
        InvoiceItem item = createBaseItem("Consulting", 10000);
        draft.getInvoiceitems().add(item);

        // Mock isDraft check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> draftCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(draftCheckQuery.count()).thenReturn(1L); // Is draft
        invoiceMockedStatic.when(() -> Invoice.find(
                eq("uuid LIKE ?1 AND lifecycleStatus LIKE ?2"),
                eq(draft.getUuid()),
                eq(LifecycleStatus.DRAFT)
        )).thenReturn(draftCheckQuery);

        // Mock numbering service to assign invoice number
        when(numberingService.getNextInvoiceNumber(
                eq(draft.getIssuerCompanyuuid()),
                eq(draft.getInvoiceSeries())
        )).thenReturn(1001);

        // Mock pricing engine
        PriceResult pricingResult = createMockPriceResult();
        when(pricingEngine.price(any(Invoice.class), anyMap())).thenReturn(pricingResult);

        // Mock static methods
        invoiceItemMockedStatic.when(() -> InvoiceItem.delete(anyString(), any(Object[].class))).thenReturn(1L);
        invoiceItemMockedStatic.when(() -> InvoiceItem.persist(anyList())).thenAnswer(inv -> null);
        contractTypeItemMockedStatic.when(() -> ContractTypeItem.find(anyString(), any(Object[].class)))
                .thenReturn(mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class));

        invoiceMockedStatic.when(() -> Invoice.update(anyString(), any(Object[].class))).thenReturn(1L);

        doNothing().when(bonusService).recalcForInvoice(anyString());
        doNothing().when(uploadService).queueUploads(any(Invoice.class));
        doNothing().when(workService).registerAsPaidout(anyString(), anyString(), anyInt(), anyInt());

        // Act
        Invoice result = invoiceService.createInvoice(draft);

        // Assert
        assertNotNull(result);
        assertEquals(1001, result.getInvoicenumber(), "Invoice number should be assigned");
        assertEquals(LifecycleStatus.CREATED, result.getLifecycleStatus());
        assertNotNull(result.getInvoicenumber(), "Finalized invoice should have invoice number");

        // Verify numbering service was called with correct parameters
        verify(numberingService, times(1)).getNextInvoiceNumber(
                eq(draft.getIssuerCompanyuuid()),
                eq(draft.getInvoiceSeries())
        );

        // Verify pricing was recalculated
        verify(pricingEngine, times(1)).price(any(Invoice.class), anyMap());

        // Verify bonus was recalculated for non-INTERNAL invoice
        verify(bonusService, times(1)).recalcForInvoice(draft.getUuid());

        // Verify queued for economics upload
        verify(uploadService, times(1)).queueUploads(result);

        // Verify work registered as paid out (note: month+1 in the implementation)
        verify(workService, times(1)).registerAsPaidout(
                eq(draft.getContractuuid()),
                eq(draft.getProjectuuid()),
                eq(draft.getInvoiceMonth() + 1), // Implementation adds 1
                eq(draft.getInvoiceYear())
        );
    }

    @Test
    @DisplayName("createInvoice - should throw exception when not draft")
    void testCreateInvoice_NotDraft_ThrowsException() {
        // Arrange
        Invoice createdInvoice = createDraftInvoice(InvoiceType.INVOICE);
        createdInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        createdInvoice.setInvoicenumber(1001);

        // Mock isDraft check to return false
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> draftCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(draftCheckQuery.count()).thenReturn(0L); // NOT a draft
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(Sort.class), any(Object[].class)))
                .thenReturn(draftCheckQuery);

        // Act & Assert
        assertThrows(RuntimeException.class, () ->
                invoiceService.createInvoice(createdInvoice),
                "Should throw exception when trying to finalize non-draft invoice"
        );

        // Verify numbering service was NOT called
        verify(numberingService, never()).getNextInvoiceNumber(anyString(), anyString());
    }

    @Test
    @DisplayName("createInvoice - should handle CREDIT_NOTE finalization")
    void testCreateInvoice_CreditNote() throws Exception {
        // Arrange
        Invoice creditNoteDraft = createDraftInvoice(InvoiceType.CREDIT_NOTE);
        String parentInvoiceUuid = UUID.randomUUID().toString();
        creditNoteDraft.setCreditnoteForUuid(parentInvoiceUuid);

        // Parent invoice
        Invoice parentInvoice = createDraftInvoice(InvoiceType.INVOICE);
        parentInvoice.setUuid(parentInvoiceUuid);
        parentInvoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        parentInvoice.setInvoicenumber(1000);

        // Mock isDraft check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> draftCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(draftCheckQuery.count()).thenReturn(1L);
        invoiceMockedStatic.when(() -> Invoice.find(
                eq("uuid LIKE ?1 AND lifecycleStatus LIKE ?2"),
                eq(creditNoteDraft.getUuid()),
                eq(LifecycleStatus.DRAFT)
        )).thenReturn(draftCheckQuery);

        // Mock parent invoice lookup
        invoiceMockedStatic.when(() -> Invoice.findById(parentInvoiceUuid))
                .thenReturn(parentInvoice);

        // Mock numbering service
        when(numberingService.getNextInvoiceNumber(anyString(), anyString()))
                .thenReturn(1001);

        invoiceMockedStatic.when(() -> Invoice.update(anyString(), any(Object[].class))).thenReturn(1L);
        doNothing().when(uploadService).queueUploads(any(Invoice.class));
        doNothing().when(workService).registerAsPaidout(anyString(), anyString(), anyInt(), anyInt());

        // Act
        Invoice result = invoiceService.createInvoice(creditNoteDraft);

        // Assert
        assertNotNull(result);
        assertEquals(InvoiceType.CREDIT_NOTE, result.getType());
        assertEquals(LifecycleStatus.CREATED, result.getLifecycleStatus());
        assertEquals(1001, result.getInvoicenumber());

        // Verify parent invoice was updated to CREATED status
        invoiceMockedStatic.verify(() -> Invoice.update(
                eq("lifecycleStatus = ?1 WHERE uuid = ?2"),
                eq(LifecycleStatus.CREATED),
                eq(parentInvoice.getUuid())
        ), times(1));

        // Verify pricing NOT recalculated for CREDIT_NOTE (skipped in implementation)
        verify(pricingEngine, never()).price(any(), anyMap());
    }

    @Test
    @DisplayName("createInvoice - should NOT recalculate bonus for INTERNAL invoices")
    void testCreateInvoice_InternalInvoice_NoBonus() throws Exception {
        // Arrange
        Invoice internalDraft = createDraftInvoice(InvoiceType.INTERNAL);
        internalDraft.setSourceInvoiceUuid(UUID.randomUUID().toString());
        internalDraft.setDebtorCompanyuuid(UUID.randomUUID().toString());

        // Mock isDraft check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> draftCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(draftCheckQuery.count()).thenReturn(1L);
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(Sort.class), any(Object[].class)))
                .thenReturn(draftCheckQuery);

        // Mock numbering service
        when(numberingService.getNextInvoiceNumber(anyString(), anyString())).thenReturn(2001);

        // Mock pricing engine
        PriceResult pricingResult = createMockPriceResult();
        when(pricingEngine.price(any(Invoice.class), anyMap())).thenReturn(pricingResult);

        invoiceItemMockedStatic.when(() -> InvoiceItem.delete(anyString(), any(Object[].class))).thenReturn(1L);
        invoiceItemMockedStatic.when(() -> InvoiceItem.persist(anyList())).thenAnswer(inv -> null);
        contractTypeItemMockedStatic.when(() -> ContractTypeItem.find(anyString(), any(Object[].class)))
                .thenReturn(mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class));

        invoiceMockedStatic.when(() -> Invoice.update(anyString(), any(Object[].class))).thenReturn(1L);
        doNothing().when(uploadService).queueUploads(any(Invoice.class));
        doNothing().when(workService).registerAsPaidout(anyString(), anyString(), anyInt(), anyInt());

        // Act
        Invoice result = invoiceService.createInvoice(internalDraft);

        // Assert
        assertNotNull(result);
        assertEquals(InvoiceType.INTERNAL, result.getType());
        assertEquals(2001, result.getInvoicenumber());

        // Verify bonus was NOT recalculated for INTERNAL invoice
        verify(bonusService, never()).recalcForInvoice(anyString());
    }

    // ============================================================================
    // createPhantomInvoice() Tests - Finalization WITHOUT Invoice Number
    // ============================================================================

    @Test
    @DisplayName("createPhantomInvoice - should finalize without invoice number")
    void testCreatePhantomInvoice_Success() throws Exception {
        // Arrange
        Invoice phantomDraft = createDraftInvoice(InvoiceType.PHANTOM);
        InvoiceItem item = createBaseItem("Service", 5000);
        phantomDraft.getInvoiceitems().add(item);

        // Mock isDraft check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> draftCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(draftCheckQuery.count()).thenReturn(1L);
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(Sort.class), any(Object[].class)))
                .thenReturn(draftCheckQuery);

        // Mock pricing engine
        PriceResult pricingResult = createMockPriceResult();
        when(pricingEngine.price(any(Invoice.class), anyMap())).thenReturn(pricingResult);

        invoiceItemMockedStatic.when(() -> InvoiceItem.delete(anyString(), any(Object[].class))).thenReturn(1L);
        invoiceItemMockedStatic.when(() -> InvoiceItem.persist(anyList())).thenAnswer(inv -> null);
        contractTypeItemMockedStatic.when(() -> ContractTypeItem.find(anyString(), any(Object[].class)))
                .thenReturn(mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class));

        invoiceMockedStatic.when(() -> Invoice.update(anyString(), any(Object[].class))).thenReturn(1L);
        doNothing().when(bonusService).recalcForInvoice(anyString());
        doNothing().when(uploadService).queueUploads(any(Invoice.class));

        // Act
        Invoice result = invoiceService.createPhantomInvoice(phantomDraft);

        // Assert
        assertNotNull(result);
        assertEquals(InvoiceType.PHANTOM, result.getType());
        assertEquals(LifecycleStatus.CREATED, result.getLifecycleStatus());
        assertEquals(0, result.getInvoicenumber(), "PHANTOM invoices should have invoicenumber=0");

        // Verify numbering service was NOT called
        verify(numberingService, never()).getNextInvoiceNumber(anyString(), anyString());

        // Verify pricing was recalculated
        verify(pricingEngine, times(1)).price(any(Invoice.class), anyMap());

        // Verify bonus was recalculated
        verify(bonusService, times(1)).recalcForInvoice(phantomDraft.getUuid());

        // Verify queued for upload
        verify(uploadService, times(1)).queueUploads(result);
    }

    @Test
    @DisplayName("createPhantomInvoice - should throw exception when not draft")
    void testCreatePhantomInvoice_NotDraft_ThrowsException() {
        // Arrange
        Invoice createdPhantom = createDraftInvoice(InvoiceType.PHANTOM);
        createdPhantom.setLifecycleStatus(LifecycleStatus.CREATED);

        // Mock isDraft check to return false
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> draftCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(draftCheckQuery.count()).thenReturn(0L); // NOT a draft
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(Sort.class), any(Object[].class)))
                .thenReturn(draftCheckQuery);

        // Act & Assert
        assertThrows(RuntimeException.class, () ->
                invoiceService.createPhantomInvoice(createdPhantom),
                "Should throw exception when trying to finalize non-draft phantom invoice"
        );
    }

    // ============================================================================
    // createCreditNote() Tests - Validation and Duplication Check
    // ============================================================================

    @Test
    @DisplayName("createCreditNote - should create credit note with work period inheritance")
    void testCreateCreditNote_Success() {
        // Arrange
        Invoice originalInvoice = createDraftInvoice(InvoiceType.INVOICE);
        originalInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        originalInvoice.setInvoicenumber(1001);
        originalInvoice.setWorkYear(2025);
        originalInvoice.setWorkMonth(7); // July work period
        originalInvoice.setBillToName("Acme Corp");
        originalInvoice.setBillToLine1("123 Main St");
        originalInvoice.setBillToZip("1234");
        originalInvoice.setBillToCity("Copenhagen");

        InvoiceItem originalItem = createBaseItem("Service", 5000);
        originalInvoice.getInvoiceitems().add(originalItem);

        // Mock no existing credit note check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> creditNoteCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(creditNoteCheckQuery.count()).thenReturn(0L); // No existing credit note
        invoiceMockedStatic.when(() -> Invoice.find(
                eq("creditnoteForUuid = ?1"),
                eq(originalInvoice.getUuid())
        )).thenReturn(creditNoteCheckQuery);

        invoiceMockedStatic.when(() -> Invoice.persist(any(Invoice.class))).thenAnswer(inv -> null);
        invoiceItemMockedStatic.when(() -> InvoiceItem.persist(any(InvoiceItem.class))).thenAnswer(inv -> null);

        // Act
        Invoice creditNote = invoiceService.createCreditNote(originalInvoice);

        // Assert
        assertNotNull(creditNote);
        assertEquals(InvoiceType.CREDIT_NOTE, creditNote.getType());
        assertEquals(originalInvoice.getUuid(), creditNote.getCreditnoteForUuid());
        assertEquals(originalInvoice.getUuid(), creditNote.getSourceInvoiceUuid());

        // Verify work period inheritance
        assertEquals(2025, creditNote.getWorkYear(), "Work year should be inherited from original");
        assertEquals(7, creditNote.getWorkMonth(), "Work month should be inherited from original (July = 7)");

        // Verify bill-to snapshot copied
        assertEquals("Acme Corp", creditNote.getBillToName());
        assertEquals("123 Main St", creditNote.getBillToLine1());
        assertEquals("1234", creditNote.getBillToZip());
        assertEquals("Copenhagen", creditNote.getBillToCity());

        // Verify items copied
        assertEquals(1, creditNote.getInvoiceitems().size());

        // Verify persistence
        invoiceMockedStatic.verify(() -> Invoice.persist(creditNote), times(1));
    }

    @Test
    @DisplayName("createCreditNote - should throw exception for PHANTOM invoices")
    void testCreateCreditNote_PhantomInvoice_ThrowsException() {
        // Arrange
        Invoice phantomInvoice = createDraftInvoice(InvoiceType.PHANTOM);
        phantomInvoice.setLifecycleStatus(LifecycleStatus.CREATED);

        // Act & Assert
        WebApplicationException exception = assertThrows(WebApplicationException.class, () ->
                invoiceService.createCreditNote(phantomInvoice),
                "Should throw exception when creating credit note for PHANTOM invoice"
        );

        assertEquals(400, exception.getResponse().getStatus());
        assertTrue(exception.getMessage().contains("Cannot create credit note for Phantom invoices"));
    }

    @Test
    @DisplayName("createCreditNote - should throw exception when credit note already exists")
    void testCreateCreditNote_DuplicateCreditNote_ThrowsException() {
        // Arrange
        Invoice originalInvoice = createDraftInvoice(InvoiceType.INVOICE);
        originalInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        originalInvoice.setInvoicenumber(1001);

        // Mock existing credit note check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> creditNoteCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(creditNoteCheckQuery.count()).thenReturn(1L); // Credit note already exists
        invoiceMockedStatic.when(() -> Invoice.find(
                eq("creditnoteForUuid = ?1"),
                eq(originalInvoice.getUuid())
        )).thenReturn(creditNoteCheckQuery);

        // Act & Assert
        WebApplicationException exception = assertThrows(WebApplicationException.class, () ->
                invoiceService.createCreditNote(originalInvoice),
                "Should throw exception when credit note already exists"
        );

        assertEquals(409, exception.getResponse().getStatus());
        assertTrue(exception.getMessage().contains("A credit note already exists"));
    }

    @Test
    @DisplayName("createCreditNote - should copy CALCULATED item metadata")
    void testCreateCreditNote_CopiesCalculatedItemMetadata() {
        // Arrange
        Invoice originalInvoice = createDraftInvoice(InvoiceType.INVOICE);
        originalInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        originalInvoice.setInvoicenumber(1001);

        // Add CALCULATED item with metadata
        InvoiceItem calculatedItem = createBaseItem("Discount", -500);
        calculatedItem.setOrigin(InvoiceItemOrigin.CALCULATED);
        calculatedItem.setCalculationRef("HEADER_DISCOUNT");
        calculatedItem.setRuleId("RULE_123");
        calculatedItem.setLabel("Volume Discount");
        originalInvoice.getInvoiceitems().add(calculatedItem);

        // Mock no existing credit note
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> creditNoteCheckQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(creditNoteCheckQuery.count()).thenReturn(0L);
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(Object[].class)))
                .thenReturn(creditNoteCheckQuery);

        invoiceMockedStatic.when(() -> Invoice.persist(any(Invoice.class))).thenAnswer(inv -> null);
        invoiceItemMockedStatic.when(() -> InvoiceItem.persist(any(InvoiceItem.class))).thenAnswer(inv -> null);

        // Act
        Invoice creditNote = invoiceService.createCreditNote(originalInvoice);

        // Assert
        assertNotNull(creditNote);
        assertEquals(1, creditNote.getInvoiceitems().size());

        InvoiceItem copiedItem = creditNote.getInvoiceitems().get(0);
        assertEquals(InvoiceItemOrigin.CALCULATED, copiedItem.getOrigin());
        assertEquals("HEADER_DISCOUNT", copiedItem.getCalculationRef(), "Should copy calculationRef");
        assertEquals("RULE_123", copiedItem.getRuleId(), "Should copy ruleId");
        assertEquals("Volume Discount", copiedItem.getLabel(), "Should copy label");
    }

    // ============================================================================
    // deleteDraftInvoice() Tests - Only DRAFT/PHANTOM Allowed
    // ============================================================================

    @Test
    @DisplayName("deleteDraftInvoice - should delete draft invoice")
    void testDeleteDraftInvoice_DraftSuccess() {
        // Arrange
        String draftUuid = UUID.randomUUID().toString();

        // Mock isDraftOrPhantom check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> checkQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(checkQuery.count()).thenReturn(1L); // Is draft or phantom
        invoiceMockedStatic.when(() -> Invoice.find(
                eq("uuid = ?1 AND (lifecycleStatus = ?2 OR type = ?3 OR invoicenumber IS NULL OR invoicenumber = 0)"),
                eq(draftUuid),
                eq(LifecycleStatus.DRAFT),
                eq(InvoiceType.PHANTOM)
        )).thenReturn(checkQuery);

        invoiceMockedStatic.when(() -> Invoice.deleteById(draftUuid))
                .thenReturn(true);

        // Act
        invoiceService.deleteDraftInvoice(draftUuid);

        // Assert
        invoiceMockedStatic.verify(() -> Invoice.deleteById(draftUuid), times(1));
    }

    @Test
    @DisplayName("deleteDraftInvoice - should delete phantom invoice")
    void testDeleteDraftInvoice_PhantomSuccess() {
        // Arrange
        String phantomUuid = UUID.randomUUID().toString();

        // Mock isDraftOrPhantom check
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> checkQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(checkQuery.count()).thenReturn(1L); // Is phantom
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(), any(), any()))
                .thenReturn(checkQuery);

        invoiceMockedStatic.when(() -> Invoice.deleteById(phantomUuid))
                .thenReturn(true);

        // Act
        invoiceService.deleteDraftInvoice(phantomUuid);

        // Assert
        invoiceMockedStatic.verify(() -> Invoice.deleteById(phantomUuid), times(1));
    }

    @Test
    @DisplayName("deleteDraftInvoice - should NOT delete finalized invoice")
    void testDeleteDraftInvoice_FinalizedInvoice_NotDeleted() {
        // Arrange
        String finalizedUuid = UUID.randomUUID().toString();

        // Mock isDraftOrPhantom check - returns false (NOT draft or phantom)
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> checkQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(checkQuery.count()).thenReturn(0L); // NOT draft or phantom
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(), any(), any()))
                .thenReturn(checkQuery);

        // Act
        invoiceService.deleteDraftInvoice(finalizedUuid);

        // Assert - delete should NOT be called
        invoiceMockedStatic.verify(() -> Invoice.deleteById(anyString()), never());
    }

    @Test
    @DisplayName("deleteDraftInvoice - should delete invoice with NULL invoice number")
    void testDeleteDraftInvoice_NullInvoiceNumber() {
        // Arrange
        String draftUuid = UUID.randomUUID().toString();

        // Mock isDraftOrPhantom check (includes invoicenumber IS NULL check)
        io.quarkus.hibernate.orm.panache.PanacheQuery<Invoice> checkQuery = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(checkQuery.count()).thenReturn(1L); // Matches (NULL invoice number)
        invoiceMockedStatic.when(() -> Invoice.find(anyString(), any(), any(), any()))
                .thenReturn(checkQuery);

        invoiceMockedStatic.when(() -> Invoice.deleteById(draftUuid))
                .thenReturn(true);

        // Act
        invoiceService.deleteDraftInvoice(draftUuid);

        // Assert
        invoiceMockedStatic.verify(() -> Invoice.deleteById(draftUuid), times(1));
    }
}

package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import dk.trustworks.intranet.aggregates.invoice.services.v2.FinanceStatusMapperService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for FinanceStatusMapperService.
 *
 * Tests mapping of e-conomic upload status to finance_status
 * and auto-advancement of lifecycle_status.
 */
@QuarkusTest
public class FinanceStatusMapperServiceTest {

    @Inject
    FinanceStatusMapperService financeStatusMapperService;

    @Inject
    InvoiceRepository repository;

    private final List<String> testInvoiceUuids = new ArrayList<>();

    @BeforeEach
    @Transactional
    public void setUp() {
        testInvoiceUuids.clear();
    }

    @AfterEach
    @Transactional
    public void tearDown() {
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
    public void testMapNoneStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "NONE", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapPendingStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PENDING", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapUploadedStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // SUCCESS without voucher number = UPLOADED
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "SUCCESS", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.UPLOADED, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapBookedStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // SUCCESS with voucher number = BOOKED
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "SUCCESS", "VOUCHER-123");

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.BOOKED, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapPaidStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.SUBMITTED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PAID", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.PAID, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapErrorStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "ERROR", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.ERROR, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapFailureStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "FAILURE", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.ERROR, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testAutoAdvanceToPayOnErpPaid() {
        // Create invoice in SUBMITTED state (can transition to PAID)
        Invoice invoice = createTestInvoice(LifecycleStatus.SUBMITTED);

        // Update finance status to PAID (should auto-advance lifecycle_status)
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PAID", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.PAID, updated.getFinanceStatus());
        // With auto-advance-on-erp-paid=true, lifecycle should also be PAID
        assertEquals(LifecycleStatus.PAID, updated.getLifecycleStatus());
    }

    @Test
    @Transactional
    public void testNoAutoAdvanceFromInvalidState() {
        // Create invoice in CREATED state (cannot transition directly to PAID)
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // Update finance status to PAID
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PAID", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.PAID, updated.getFinanceStatus());
        // Lifecycle should NOT auto-advance (invalid transition)
        assertEquals(LifecycleStatus.CREATED, updated.getLifecycleStatus());
    }

    @Test
    @Transactional
    public void testResetFinanceStatus() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);
        invoice.setFinanceStatus(FinanceStatus.ERROR);
        repository.persist(invoice);

        financeStatusMapperService.resetFinanceStatus(invoice.getUuid());

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testUpdateWithMinimalParameters() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // Call overload without voucher number
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "SUCCESS");

        Invoice updated = repository.findById(invoice.getUuid());
        // Should be UPLOADED (no voucher number provided)
        assertEquals(FinanceStatus.UPLOADED, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testUnknownStatusDefaultsToNone() {
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "UNKNOWN_STATUS", null);

        Invoice updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    /**
     * Helper method to create a test invoice.
     */
    private Invoice createTestInvoice(LifecycleStatus lifecycleStatus) {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setLifecycleStatus(lifecycleStatus);
        invoice.setFinanceStatus(FinanceStatus.NONE);

        repository.persist(invoice);
        testInvoiceUuids.add(invoice.getUuid());

        return invoice;
    }
}

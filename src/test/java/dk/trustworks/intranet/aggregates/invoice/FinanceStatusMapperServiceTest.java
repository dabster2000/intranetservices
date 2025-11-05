package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceV2;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceV2Repository;
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
    InvoiceV2Repository repository;

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
            InvoiceV2 invoice = repository.findById(uuid);
            if (invoice != null) {
                repository.delete(invoice);
            }
        }
        testInvoiceUuids.clear();
    }

    @Test
    @Transactional
    public void testMapNoneStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "NONE", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapPendingStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PENDING", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapUploadedStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        // SUCCESS without voucher number = UPLOADED
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "SUCCESS", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.UPLOADED, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapBookedStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        // SUCCESS with voucher number = BOOKED
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "SUCCESS", "VOUCHER-123");

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.BOOKED, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapPaidStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.SUBMITTED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PAID", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.PAID, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapErrorStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "ERROR", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.ERROR, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testMapFailureStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "FAILURE", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.ERROR, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testAutoAdvanceToPayOnErpPaid() {
        // Create invoice in SUBMITTED state (can transition to PAID)
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.SUBMITTED);

        // Update finance status to PAID (should auto-advance lifecycle_status)
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PAID", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.PAID, updated.getFinanceStatus());
        // With auto-advance-on-erp-paid=true, lifecycle should also be PAID
        assertEquals(LifecycleStatus.PAID, updated.getLifecycleStatus());
    }

    @Test
    @Transactional
    public void testNoAutoAdvanceFromInvalidState() {
        // Create invoice in CREATED state (cannot transition directly to PAID)
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        // Update finance status to PAID
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "PAID", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.PAID, updated.getFinanceStatus());
        // Lifecycle should NOT auto-advance (invalid transition)
        assertEquals(LifecycleStatus.CREATED, updated.getLifecycleStatus());
    }

    @Test
    @Transactional
    public void testResetFinanceStatus() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);
        invoice.setFinanceStatus(FinanceStatus.ERROR);
        repository.persist(invoice);

        financeStatusMapperService.resetFinanceStatus(invoice.getUuid());

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testUpdateWithMinimalParameters() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        // Call overload without voucher number
        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "SUCCESS");

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        // Should be UPLOADED (no voucher number provided)
        assertEquals(FinanceStatus.UPLOADED, updated.getFinanceStatus());
    }

    @Test
    @Transactional
    public void testUnknownStatusDefaultsToNone() {
        InvoiceV2 invoice = createTestInvoice(LifecycleStatus.CREATED);

        financeStatusMapperService.updateFinanceStatus(invoice.getUuid(), "UNKNOWN_STATUS", null);

        InvoiceV2 updated = repository.findById(invoice.getUuid());
        assertEquals(FinanceStatus.NONE, updated.getFinanceStatus());
    }

    /**
     * Helper method to create a test invoice.
     */
    private InvoiceV2 createTestInvoice(LifecycleStatus lifecycleStatus) {
        InvoiceV2 invoice = new InvoiceV2();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setLifecycleStatus(lifecycleStatus);
        invoice.setFinanceStatus(FinanceStatus.NONE);

        repository.persist(invoice);
        testInvoiceUuids.add(invoice.getUuid());

        return invoice;
    }
}

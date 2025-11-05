package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.events.InvoiceLifecycleChanged;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceStateMachine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that InvoiceStateMachine emits lifecycle events correctly.
 *
 * Validates Phase 2 Task 2.1 acceptance criteria:
 * - Events are emitted for all lifecycle transitions
 * - Events contain correct data (invoiceUuid, oldStatus, newStatus, invoiceType, timestamp)
 * - Events can be observed using CDI @Observes pattern
 */
@QuarkusTest
public class InvoiceLifecycleEventTest {

    @Inject
    InvoiceStateMachine stateMachine;

    @Inject
    TestEventListener eventListener;

    @BeforeEach
    public void setUp() {
        // Clear events before each test
        eventListener.clear();
    }

    @Test
    public void testEventEmittedOnValidTransition() {
        // Given: An invoice in DRAFT status
        Invoice invoice = createTestInvoice(LifecycleStatus.DRAFT);

        // When: Transitioning to CREATED
        stateMachine.transition(invoice, LifecycleStatus.CREATED);

        // Then: Event should be emitted
        assertEquals(1, eventListener.getEvents().size());

        InvoiceLifecycleChanged event = eventListener.getEvents().get(0);
        assertEquals(invoice.getUuid(), event.invoiceUuid());
        assertEquals(LifecycleStatus.DRAFT, event.oldStatus());
        assertEquals(LifecycleStatus.CREATED, event.newStatus());
        assertEquals(InvoiceType.INVOICE, event.invoiceType());
        assertNotNull(event.timestamp());
    }

    @Test
    public void testNoEventEmittedForIdempotentTransition() {
        // Given: An invoice already in CREATED status
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // When: Transitioning to CREATED again (no-op)
        stateMachine.transition(invoice, LifecycleStatus.CREATED);

        // Then: No event should be emitted (idempotent operation)
        assertEquals(0, eventListener.getEvents().size());
    }

    @Test
    public void testEventEmittedForEachTransitionInChain() {
        // Given: An invoice in DRAFT status
        Invoice invoice = createTestInvoice(LifecycleStatus.DRAFT);

        // When: Transitioning through the full lifecycle
        stateMachine.transition(invoice, LifecycleStatus.CREATED);
        stateMachine.transition(invoice, LifecycleStatus.SUBMITTED);
        stateMachine.transition(invoice, LifecycleStatus.PAID);

        // Then: Three events should be emitted
        assertEquals(3, eventListener.getEvents().size());

        // Verify first transition
        InvoiceLifecycleChanged event1 = eventListener.getEvents().get(0);
        assertEquals(LifecycleStatus.DRAFT, event1.oldStatus());
        assertEquals(LifecycleStatus.CREATED, event1.newStatus());

        // Verify second transition
        InvoiceLifecycleChanged event2 = eventListener.getEvents().get(1);
        assertEquals(LifecycleStatus.CREATED, event2.oldStatus());
        assertEquals(LifecycleStatus.SUBMITTED, event2.newStatus());

        // Verify third transition
        InvoiceLifecycleChanged event3 = eventListener.getEvents().get(2);
        assertEquals(LifecycleStatus.SUBMITTED, event3.oldStatus());
        assertEquals(LifecycleStatus.PAID, event3.newStatus());
        assertTrue(event3.isTerminalTransition());
    }

    @Test
    public void testEventContainsCancellationTransition() {
        // Given: An invoice in CREATED status
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // When: Cancelling the invoice
        stateMachine.transition(invoice, LifecycleStatus.CANCELLED);

        // Then: Event should reflect cancellation
        assertEquals(1, eventListener.getEvents().size());

        InvoiceLifecycleChanged event = eventListener.getEvents().get(0);
        assertEquals(LifecycleStatus.CREATED, event.oldStatus());
        assertEquals(LifecycleStatus.CANCELLED, event.newStatus());
        assertTrue(event.isTerminalTransition());
    }

    @Test
    public void testEventHasTransitionHelperMethods() {
        // Given: An invoice transitioning from CREATED to SUBMITTED
        Invoice invoice = createTestInvoice(LifecycleStatus.CREATED);

        // When: Transitioning to SUBMITTED
        stateMachine.transition(invoice, LifecycleStatus.SUBMITTED);

        // Then: Event helper methods should work correctly
        InvoiceLifecycleChanged event = eventListener.getEvents().get(0);
        assertTrue(event.isTransition(LifecycleStatus.CREATED, LifecycleStatus.SUBMITTED));
        assertFalse(event.isTransition(LifecycleStatus.DRAFT, LifecycleStatus.CREATED));
        assertFalse(event.isTerminalTransition());
    }

    @Test
    public void testEventWithDifferentInvoiceTypes() {
        // Given: A credit note invoice in DRAFT status
        Invoice creditNote = createTestInvoice(LifecycleStatus.DRAFT);
        creditNote.setType(InvoiceType.CREDIT_NOTE);

        // When: Transitioning to CREATED
        stateMachine.transition(creditNote, LifecycleStatus.CREATED);

        // Then: Event should contain correct invoice type
        assertEquals(1, eventListener.getEvents().size());
        InvoiceLifecycleChanged event = eventListener.getEvents().get(0);
        assertEquals(InvoiceType.CREDIT_NOTE, event.invoiceType());
    }

    // Helper method to create test invoices
    private Invoice createTestInvoice(LifecycleStatus initialStatus) {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setLifecycleStatus(initialStatus);
        invoice.setType(InvoiceType.INVOICE);
        invoice.setIssuerCompanyuuid("test-company-uuid");
        return invoice;
    }

    /**
     * Test CDI bean that observes InvoiceLifecycleChanged events.
     * Used to verify that events are actually being emitted and can be observed.
     */
    @Singleton
    public static class TestEventListener {
        private final CopyOnWriteArrayList<InvoiceLifecycleChanged> events = new CopyOnWriteArrayList<>();

        void onLifecycleChange(@Observes InvoiceLifecycleChanged event) {
            events.add(event);
        }

        public CopyOnWriteArrayList<InvoiceLifecycleChanged> getEvents() {
            return events;
        }

        public void clear() {
            events.clear();
        }
    }
}

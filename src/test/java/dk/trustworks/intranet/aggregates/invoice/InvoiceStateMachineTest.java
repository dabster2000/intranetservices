package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceStateMachine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InvoiceStateMachine.
 *
 * Validates state transition rules for invoice lifecycle.
 */
@QuarkusTest
public class InvoiceStateMachineTest {

    @Inject
    InvoiceStateMachine stateMachine;

    @Test
    public void testValidTransitions() {
        // DRAFT can transition to CREATED or CANCELLED
        assertTrue(stateMachine.canTransition(LifecycleStatus.DRAFT, LifecycleStatus.CREATED));
        assertTrue(stateMachine.canTransition(LifecycleStatus.DRAFT, LifecycleStatus.CANCELLED));

        // CREATED can transition to SUBMITTED or CANCELLED
        assertTrue(stateMachine.canTransition(LifecycleStatus.CREATED, LifecycleStatus.SUBMITTED));
        assertTrue(stateMachine.canTransition(LifecycleStatus.CREATED, LifecycleStatus.CANCELLED));

        // SUBMITTED can transition to PAID or CANCELLED
        assertTrue(stateMachine.canTransition(LifecycleStatus.SUBMITTED, LifecycleStatus.PAID));
        assertTrue(stateMachine.canTransition(LifecycleStatus.SUBMITTED, LifecycleStatus.CANCELLED));
    }

    @Test
    public void testInvalidTransitions() {
        // Cannot skip states
        assertFalse(stateMachine.canTransition(LifecycleStatus.DRAFT, LifecycleStatus.SUBMITTED));
        assertFalse(stateMachine.canTransition(LifecycleStatus.DRAFT, LifecycleStatus.PAID));
        assertFalse(stateMachine.canTransition(LifecycleStatus.CREATED, LifecycleStatus.PAID));

        // Cannot transition from terminal states
        assertFalse(stateMachine.canTransition(LifecycleStatus.PAID, LifecycleStatus.DRAFT));
        assertFalse(stateMachine.canTransition(LifecycleStatus.PAID, LifecycleStatus.CREATED));
        assertFalse(stateMachine.canTransition(LifecycleStatus.PAID, LifecycleStatus.SUBMITTED));
        assertFalse(stateMachine.canTransition(LifecycleStatus.PAID, LifecycleStatus.CANCELLED));

        assertFalse(stateMachine.canTransition(LifecycleStatus.CANCELLED, LifecycleStatus.DRAFT));
        assertFalse(stateMachine.canTransition(LifecycleStatus.CANCELLED, LifecycleStatus.CREATED));
        assertFalse(stateMachine.canTransition(LifecycleStatus.CANCELLED, LifecycleStatus.SUBMITTED));
        assertFalse(stateMachine.canTransition(LifecycleStatus.CANCELLED, LifecycleStatus.PAID));
    }

    @Test
    public void testIdempotentTransitions() {
        // Same state transitions are allowed (no-op)
        assertTrue(stateMachine.canTransition(LifecycleStatus.DRAFT, LifecycleStatus.DRAFT));
        assertTrue(stateMachine.canTransition(LifecycleStatus.CREATED, LifecycleStatus.CREATED));
        assertTrue(stateMachine.canTransition(LifecycleStatus.SUBMITTED, LifecycleStatus.SUBMITTED));
        assertTrue(stateMachine.canTransition(LifecycleStatus.PAID, LifecycleStatus.PAID));
        assertTrue(stateMachine.canTransition(LifecycleStatus.CANCELLED, LifecycleStatus.CANCELLED));
    }

    @Test
    public void testTerminalStates() {
        // PAID and CANCELLED are terminal
        assertTrue(stateMachine.isTerminalState(LifecycleStatus.PAID));
        assertTrue(stateMachine.isTerminalState(LifecycleStatus.CANCELLED));

        // Other states are not terminal
        assertFalse(stateMachine.isTerminalState(LifecycleStatus.DRAFT));
        assertFalse(stateMachine.isTerminalState(LifecycleStatus.CREATED));
        assertFalse(stateMachine.isTerminalState(LifecycleStatus.SUBMITTED));
    }

    @Test
    public void testGetValidNextStates() {
        // DRAFT can go to CREATED or CANCELLED
        LifecycleStatus[] fromDraft = stateMachine.getValidNextStates(LifecycleStatus.DRAFT);
        assertEquals(2, fromDraft.length);
        assertArrayEquals(new LifecycleStatus[]{LifecycleStatus.CREATED, LifecycleStatus.CANCELLED}, fromDraft);

        // CREATED can go to SUBMITTED or CANCELLED
        LifecycleStatus[] fromCreated = stateMachine.getValidNextStates(LifecycleStatus.CREATED);
        assertEquals(2, fromCreated.length);
        assertArrayEquals(new LifecycleStatus[]{LifecycleStatus.SUBMITTED, LifecycleStatus.CANCELLED}, fromCreated);

        // SUBMITTED can go to PAID or CANCELLED
        LifecycleStatus[] fromSubmitted = stateMachine.getValidNextStates(LifecycleStatus.SUBMITTED);
        assertEquals(2, fromSubmitted.length);
        assertArrayEquals(new LifecycleStatus[]{LifecycleStatus.PAID, LifecycleStatus.CANCELLED}, fromSubmitted);

        // Terminal states have no next states
        LifecycleStatus[] fromPaid = stateMachine.getValidNextStates(LifecycleStatus.PAID);
        assertEquals(0, fromPaid.length);

        LifecycleStatus[] fromCancelled = stateMachine.getValidNextStates(LifecycleStatus.CANCELLED);
        assertEquals(0, fromCancelled.length);
    }

    @Test
    public void testTransitionWithInvalidState() {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        invoice.setType(InvoiceType.INVOICE);
        invoice.setIssuerCompanyuuid("test-company-uuid");

        // Try to skip from DRAFT to PAID (invalid)
        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> stateMachine.transition(invoice, LifecycleStatus.PAID)
        );

        assertTrue(exception.getMessage().contains("Invalid lifecycle transition"));
        assertTrue(exception.getMessage().contains("DRAFT"));
        assertTrue(exception.getMessage().contains("PAID"));
    }

    @Test
    public void testTransitionUpdatesInvoice() {
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        invoice.setType(InvoiceType.INVOICE);
        invoice.setIssuerCompanyuuid("test-company-uuid");

        // Valid transition
        stateMachine.transition(invoice, LifecycleStatus.CREATED);

        // Verify status changed
        assertEquals(LifecycleStatus.CREATED, invoice.getLifecycleStatus());
        // Verify updated_at was set
        assertNotNull(invoice.getUpdatedAt());
    }
}

package dk.trustworks.intranet.aggregates.invoice.services.v2;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;

/**
 * State machine for enforcing invoice lifecycle transitions.
 *
 * Valid state transitions:
 * DRAFT → CREATED → SUBMITTED → PAID
 *   ↘                      ↘
 *    CANCELLED            CANCELLED
 *
 * Terminal states (no transitions out): PAID, CANCELLED
 */
@ApplicationScoped
public class InvoiceStateMachine {

    /**
     * Validate if a lifecycle status transition is allowed.
     *
     * @param from The current lifecycle status
     * @param to The target lifecycle status
     * @return true if transition is allowed, false otherwise
     */
    public boolean canTransition(LifecycleStatus from, LifecycleStatus to) {
        if (from == to) {
            // No-op transitions are allowed (idempotent)
            return true;
        }

        return switch (from) {
            case DRAFT -> to == LifecycleStatus.CREATED || to == LifecycleStatus.CANCELLED;
            case CREATED -> to == LifecycleStatus.SUBMITTED || to == LifecycleStatus.CANCELLED;
            case SUBMITTED -> to == LifecycleStatus.PAID || to == LifecycleStatus.CANCELLED;
            case PAID, CANCELLED -> false; // Terminal states - no transitions allowed
        };
    }

    /**
     * Transition an invoice to a new lifecycle status.
     * Validates the transition is allowed and updates the invoice entity.
     *
     * @param invoice The invoice to transition
     * @param newStatus The target lifecycle status
     * @throws WebApplicationException if transition is not allowed
     */
    @Transactional
    public void transition(Invoice invoice, LifecycleStatus newStatus) {
        LifecycleStatus currentStatus = invoice.getLifecycleStatus();

        if (currentStatus == newStatus) {
            Log.debugf("Invoice %s already in status %s, no transition needed",
                      invoice.getUuid(), newStatus);
            return;
        }

        if (!canTransition(currentStatus, newStatus)) {
            String msg = String.format(
                "Invalid lifecycle transition %s → %s for invoice %s",
                currentStatus, newStatus, invoice.getUuid()
            );
            Log.error(msg);
            throw new WebApplicationException(msg, Response.Status.BAD_REQUEST);
        }

        LifecycleStatus oldStatus = invoice.getLifecycleStatus();
        invoice.setLifecycleStatus(newStatus);
        invoice.setUpdatedAt(LocalDateTime.now());

        Log.infof("Invoice %s transitioned: %s → %s", invoice.getUuid(), oldStatus, newStatus);
    }

    /**
     * Check if an invoice is in a terminal state (no further transitions allowed).
     *
     * @param status The lifecycle status to check
     * @return true if status is terminal (PAID or CANCELLED)
     */
    public boolean isTerminalState(LifecycleStatus status) {
        return status == LifecycleStatus.PAID || status == LifecycleStatus.CANCELLED;
    }

    /**
     * Get all valid next states from the current state.
     *
     * @param from The current lifecycle status
     * @return Array of valid next states
     */
    public LifecycleStatus[] getValidNextStates(LifecycleStatus from) {
        return switch (from) {
            case DRAFT -> new LifecycleStatus[]{LifecycleStatus.CREATED, LifecycleStatus.CANCELLED};
            case CREATED -> new LifecycleStatus[]{LifecycleStatus.SUBMITTED, LifecycleStatus.CANCELLED};
            case SUBMITTED -> new LifecycleStatus[]{LifecycleStatus.PAID, LifecycleStatus.CANCELLED};
            case PAID, CANCELLED -> new LifecycleStatus[]{};
        };
    }
}

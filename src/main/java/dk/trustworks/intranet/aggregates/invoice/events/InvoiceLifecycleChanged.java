package dk.trustworks.intranet.aggregates.invoice.events;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;

import java.time.LocalDateTime;

/**
 * Event emitted when an invoice lifecycle status changes.
 *
 * This event is fired by the InvoiceStateMachine whenever a valid state transition occurs.
 * It can be observed using Quarkus CDI event observers to trigger side effects such as:
 * - Logging state transitions
 * - Sending notifications (email, Slack)
 * - Triggering external integrations (e-conomic sync)
 * - Updating analytics/metrics
 *
 * Example observer:
 * <pre>
 * {@code
 * @ApplicationScoped
 * public class InvoiceEventLogger {
 *
 *     void onLifecycleChange(@Observes InvoiceLifecycleChanged event) {
 *         Log.infof("Invoice %s lifecycle changed: %s → %s (type: %s)",
 *                  event.invoiceUuid(),
 *                  event.oldStatus(),
 *                  event.newStatus(),
 *                  event.invoiceType());
 *     }
 * }
 * }
 * </pre>
 *
 * @param invoiceUuid The unique identifier of the invoice
 * @param oldStatus The previous lifecycle status
 * @param newStatus The new lifecycle status
 * @param invoiceType The type of invoice (INVOICE, CREDIT_NOTE, etc.)
 * @param timestamp The time when the transition occurred
 */
public record InvoiceLifecycleChanged(
    String invoiceUuid,
    LifecycleStatus oldStatus,
    LifecycleStatus newStatus,
    InvoiceType invoiceType,
    LocalDateTime timestamp
) {
    /**
     * Compact constructor for validation.
     *
     * @throws IllegalArgumentException if any required field is null or blank
     */
    public InvoiceLifecycleChanged {
        if (invoiceUuid == null || invoiceUuid.isBlank()) {
            throw new IllegalArgumentException("invoiceUuid cannot be null or blank");
        }
        if (oldStatus == null) {
            throw new IllegalArgumentException("oldStatus cannot be null");
        }
        if (newStatus == null) {
            throw new IllegalArgumentException("newStatus cannot be null");
        }
        if (invoiceType == null) {
            throw new IllegalArgumentException("invoiceType cannot be null");
        }
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    /**
     * Convenience method to check if this is a terminal state transition.
     *
     * @return true if the new status is PAID or CANCELLED
     */
    public boolean isTerminalTransition() {
        return newStatus == LifecycleStatus.PAID || newStatus == LifecycleStatus.CANCELLED;
    }

    /**
     * Convenience method to check if this is a specific transition.
     *
     * @param from Expected old status
     * @param to Expected new status
     * @return true if this event represents the specified transition
     */
    public boolean isTransition(LifecycleStatus from, LifecycleStatus to) {
        return oldStatus == from && newStatus == to;
    }
}

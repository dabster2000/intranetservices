package dk.trustworks.intranet.aggregates.invoice.events;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Example event observer for InvoiceLifecycleChanged events.
 *
 * This demonstrates how to observe and react to invoice lifecycle transitions.
 * In production, you might use this pattern to:
 * - Send notifications (email, Slack)
 * - Trigger external integrations (e-conomic sync)
 * - Update analytics/metrics
 * - Perform audit logging
 *
 * To observe events, simply inject the event type using @Observes in a CDI bean method.
 */
@ApplicationScoped
public class InvoiceEventLogger {

    /**
     * Observes all invoice lifecycle changes and logs them.
     *
     * @param event The lifecycle change event
     */
    void onLifecycleChange(@Observes InvoiceLifecycleChanged event) {
        Log.infof("EVENT: Invoice %s lifecycle changed: %s → %s (type: %s) at %s",
                 event.invoiceUuid(),
                 event.oldStatus(),
                 event.newStatus(),
                 event.invoiceType(),
                 event.timestamp());

        // Example: Perform specific actions based on transition type
        if (event.isTerminalTransition()) {
            Log.infof("Invoice %s reached terminal state: %s",
                     event.invoiceUuid(),
                     event.newStatus());
        }

        // Example: Trigger specific actions for specific transitions
        if (event.isTransition(
            dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus.CREATED,
            dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus.SUBMITTED)) {
            Log.infof("Invoice %s was submitted - could trigger e-conomic sync here",
                     event.invoiceUuid());
        }
    }
}

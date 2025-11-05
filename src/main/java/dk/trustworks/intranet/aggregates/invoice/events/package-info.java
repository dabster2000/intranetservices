/**
 * Invoice lifecycle events package.
 *
 * <h2>Overview</h2>
 * This package contains events emitted during invoice lifecycle transitions.
 * Events use the Quarkus CDI event system for loose coupling between components.
 *
 * <h2>Event Types</h2>
 * <ul>
 *   <li>{@link dk.trustworks.intranet.aggregates.invoice.events.InvoiceLifecycleChanged} - Emitted on all lifecycle transitions</li>
 * </ul>
 *
 * <h2>Observing Events</h2>
 * To observe invoice events, create a CDI bean with an observer method:
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class MyInvoiceListener {
 *
 *     void onLifecycleChange(@Observes InvoiceLifecycleChanged event) {
 *         Log.infof("Invoice %s: %s → %s",
 *                  event.invoiceUuid(),
 *                  event.oldStatus(),
 *                  event.newStatus());
 *
 *         // React to specific transitions
 *         if (event.isTransition(LifecycleStatus.CREATED, LifecycleStatus.SUBMITTED)) {
 *             // Invoice was submitted - trigger e-conomic sync
 *             economicsService.syncInvoice(event.invoiceUuid());
 *         }
 *
 *         // React to terminal states
 *         if (event.isTerminalTransition()) {
 *             // Invoice reached PAID or CANCELLED state
 *             notificationService.sendFinalizationEmail(event.invoiceUuid());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Event Emission</h2>
 * Events are automatically emitted by {@link dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceStateMachine}
 * for all successful lifecycle transitions. You do not need to manually emit events when using the state machine.
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Logging and audit trails</li>
 *   <li>Sending notifications (email, Slack)</li>
 *   <li>Triggering external integrations (e-conomic sync)</li>
 *   <li>Updating analytics and metrics</li>
 *   <li>Triggering workflow automation</li>
 * </ul>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li>Events are immutable Java records</li>
 *   <li>Events are fired synchronously within the same transaction</li>
 *   <li>Multiple observers can listen to the same event</li>
 *   <li>Observer order is not guaranteed unless you use @Priority</li>
 *   <li>Observers should be fast - heavy processing should be async</li>
 * </ul>
 *
 * @see dk.trustworks.intranet.aggregates.invoice.events.InvoiceLifecycleChanged
 * @see dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceStateMachine
 * @since Phase 2 Task 2.1
 */
package dk.trustworks.intranet.aggregates.invoice.events;

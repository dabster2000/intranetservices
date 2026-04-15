package dk.trustworks.intranet.aggregates.invoice.economics.notifications;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Sends operator-visible alerts when a PENDING_REVIEW invoice is automatically
 * reverted to DRAFT by the stale-draft cleanup batchlet.
 *
 * <p>No dedicated user-notification channel exists in the project at this phase.
 * Alerts are written to the application log at WARN level so they surface in
 * production log aggregation (CloudWatch / ECS log groups).
 *
 * <p>SPEC-INV-001 §9.5.
 *
 * TODO(phase-H-followup): integrate with a real user-notification channel
 * (e.g. push notification, email, or in-app alert) once that infrastructure
 * exists. Replace the LOG.warn call with the channel-specific call and keep
 * the method signature unchanged.
 */
@ApplicationScoped
public class PendingReviewNotificationService {

    private static final Logger LOG = Logger.getLogger(PendingReviewNotificationService.class);

    /**
     * Logs a WARN-level alert that a draft invoice was automatically reverted to DRAFT
     * after sitting in PENDING_REVIEW for more than 7 days without being booked.
     *
     * @param invoice the invoice that was reverted — must not be null
     */
    public void notifyAutoReverted(Invoice invoice) {
        LOG.warnf(
                "PENDING_REVIEW invoice auto-reverted to DRAFT after 7-day timeout. "
                + "invoiceUuid=%s draftNumber=%s clientname='%s'. "
                + "Action required: recreate the draft or contact the accountant.",
                invoice.getUuid(),
                invoice.getEconomicsDraftNumber(),
                invoice.getClientname()
        );
    }
}

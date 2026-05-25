package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;

/**
 * Reference to the CREDIT_NOTE that cancels a client invoice, projected onto
 * the internal-invoices controlling page row when R3 fires (cancelled source
 * with an active non-DRAFT internal invoice).
 *
 * <p>Populated only on the {@code clientDto} of pairs returned by
 * {@link dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceControllingService#findCrossCompanyInvoicesWithInternal}
 * — {@code null} when the source is not cancelled, or when it is cancelled but
 * its linked internal is DRAFT/absent (in which case the pair is filtered out).
 *
 * <p>This is a value object — no entity identity, immutable, defined entirely
 * by its component fields.
 */
@RegisterForReflection
public record CancellingCreditNoteRef(
        String uuid,
        int invoicenumber,
        LocalDate invoicedate,
        InvoiceStatus status
) {}

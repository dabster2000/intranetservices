package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceControlStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight invoice representation for API responses.
 * Contains essential header fields and the list of lines with a per-line
 * cross-company flag.
 *
 * <p>{@code cancellingCreditNote} is populated only on the client invoice of a
 * cross-company pair when the client invoice is FULLY credited (sum-based, see
 * {@code fullyCredited}) AND its linked INTERNAL invoice is still active (status
 * PENDING_REVIEW, QUEUED, or CREATED). It deep-links to the LATEST live credit
 * note. It is {@code null} otherwise — including for internal-invoice DTOs, for
 * partially credited clients, and for pairs where the pricing pipeline filtered
 * the source out entirely.
 *
 * <p>{@code creditNote} is populated only on the INTERNAL row of a pair when a
 * CREDIT_NOTE reverses that internal (reverse {@code creditnote_for_uuid} lookup;
 * internals keep the strict 1:1 model). Null everywhere else — including client rows.
 *
 * <p>{@code creditedAmount} / {@code fullyCredited} carry the sum-based credited
 * measure for client invoices: Σ(hours×rate) over items of LIVE credit notes
 * (CREATED/QUEUED/PENDING_REVIEW) pointing at the invoice, and whether that sum
 * covers the invoice within the shared 1.0 DKK tolerance. A client invoice may
 * carry any number of partial credit notes (V386). Populated by the pairs and
 * missing-internal queries; {@code 0.0} / {@code false} on DTOs from endpoints
 * that do not compute the measure (internal rows, client&lt;internal, multiple
 * internals).
 */
@RegisterForReflection
public record SimpleInvoiceDTO(
        String uuid,
        int invoicenumber,
        LocalDate invoicedate,
        String creditorCompanyUuid,
        String creditorCompanyName,
        String clientName,
        InvoiceStatus status,
        EconomicsInvoiceStatus economicsStatus,
        double totalAmountNoTax,
        List<InvoiceLineDTO> lines,
        InvoiceControlStatus controlStatus,
        String controlNote,
        LocalDateTime controlStatusUpdatedAt,
        String controlStatusUpdatedBy,
        boolean internalInvoiceSkip,
        String internalInvoiceSkipNote,
        LocalDateTime internalInvoiceSkipAt,
        String internalInvoiceSkipBy,
        CancellingCreditNoteRef cancellingCreditNote,
        CancellingCreditNoteRef creditNote,
        double creditedAmount,
        boolean fullyCredited
) {}

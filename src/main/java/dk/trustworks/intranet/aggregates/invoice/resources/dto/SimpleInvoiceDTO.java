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
 * cross-company pair when the client invoice has been cancelled by a CREDIT_NOTE
 * AND its linked INTERNAL invoice is still active (status PENDING_REVIEW, QUEUED,
 * or CREATED). It is {@code null} otherwise — including for internal-invoice DTOs
 * and for pairs where the pricing pipeline filtered the source out entirely.
 *
 * <p>{@code creditNote} is populated only on the INTERNAL row of a pair when a
 * CREDIT_NOTE reverses that internal (reverse {@code creditnote_for_uuid} lookup).
 * Null everywhere else — including client rows.
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
        CancellingCreditNoteRef creditNote
) {}

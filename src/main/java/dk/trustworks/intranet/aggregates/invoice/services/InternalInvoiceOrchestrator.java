package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttempt;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptWriter;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Coordinates the two sides of an INTERNAL (or INTERNAL_SERVICE) invoice.
 *
 * <p>ISSUER side: routed through {@link InvoiceFinalizationOrchestrator} (standard Q2C draft+book).
 * DEBTOR side: voucher posted to the debtor company's e-conomic via
 * {@link dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService#sendVoucherToCompany}.
 *
 * <p>If either side fails the economics_status is set to {@code PARTIALLY_UPLOADED} so the
 * retry batch can detect and re-attempt. A full success leaves status at {@code BOOKED}
 * (set by the issuer side's {@code bookDraft}).
 *
 * <p>Credit notes are handled transparently: negative {@code grandTotal} values flow through
 * both sides unchanged — the ISSUER mapper already produces negative line amounts (H5),
 * and {@code EconomicsInvoiceService.sendVoucherToCompany} uses {@code getGrandTotal()}
 * directly, so negative amounts produce a supplier credit entry on the DEBTOR side.
 *
 * SPEC-INV-001 §4.5, §4.7, §10.
 */
@JBossLog
@ApplicationScoped
public class InternalInvoiceOrchestrator {

    @Inject
    InvoiceFinalizationOrchestrator issuerSide;

    @Inject
    dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService economicsInvoiceService;

    @Inject
    InvoiceRepository invoices;

    @Inject
    EconomicsAgreementResolver agreements;

    @Inject
    @RestClient
    EconomicsBookingApiClient bookApi;

    @Inject
    InvoiceBookingAttemptRepository attemptRepo;

    @Inject
    InvoiceBookingAttemptWriter attemptWriter;

    /**
     * Creates an e-conomic draft on the ISSUER side (step 1).
     *
     * <p>This is identical to the normal flow — the DEBTOR side only fires at book time.
     *
     * @param invoiceUuid the UUID of the INTERNAL or INTERNAL_SERVICE invoice
     * @return the updated invoice entity (status = PENDING_REVIEW)
     */
    @Transactional
    public Invoice createDraft(String invoiceUuid) {
        assertInternalType(invoiceUuid);
        return issuerSide.createDraft(invoiceUuid);
    }

    /**
     * Books the ISSUER-side draft and immediately posts the DEBTOR-side supplier voucher.
     *
     * <p>If the DEBTOR-side post fails the invoice economics_status is set to
     * {@code PARTIALLY_UPLOADED} so a retry batchlet can re-attempt.
     *
     * @param invoiceUuid the UUID of the INTERNAL or INTERNAL_SERVICE invoice
     * @param sendBy      optional delivery method (null | "ean" | "Email")
     * @return the updated invoice entity
     */
    // Deliberately NOT @Transactional — see InvoiceService.bookInvoice. The irreversible
    // e-conomic POST must not sit inside a transaction that can roll back underneath it.
    public Invoice bookDraft(String invoiceUuid, String sendBy) {
        assertInternalType(invoiceUuid);
        return issuerSide.bookDraft(invoiceUuid, sendBy);
        // DEBTOR-side is handled inline in InvoiceFinalizationOrchestrator.bookDraft
        // via the INTERNAL-type guard added in H11. No duplicate call needed here.
    }

    /**
     * Convenience entry point for the queued batchlet (H12) — creates draft and books in
     * a single call, with no review step, using {@code sendBy = null}.
     *
     * @param invoiceUuid the UUID of the INTERNAL or INTERNAL_SERVICE invoice
     * @return the updated invoice entity (status = CREATED if both sides succeed)
     */
    // @Transactional is LOAD-BEARING here and must stay. createDraft and bookDraft run in ONE
    // transaction so the recalculator-set, @Transient grandTotal survives from createDraft into
    // the debtor-side voucher (InvoiceResource:378-381). EconomicsInvoiceService reads it with a
    // silent `!= null ? : 0.0` fallback, so splitting this would post 0.00 DKK intercompany
    // vouchers with no error at all.
    @Transactional
    public Invoice finalizeAutomatically(String invoiceUuid) {
        assertInternalType(invoiceUuid);
        issuerSide.createDraft(invoiceUuid);
        return issuerSide.bookDraft(invoiceUuid, null);
        // DEBTOR side fires inline in bookDraft — no additional call required.
    }

    /**
     * Entry point for the manual "force-create-queued" REST endpoint (§9.2).
     *
     * <p>Validates the invoice is {@code QUEUED} and INTERNAL / INTERNAL_SERVICE, sets
     * {@code invoicedate = today} and {@code duedate = tomorrow} (mirroring the nightly
     * batchlet), then delegates to {@link #finalizeAutomatically(String)}.
     *
     * <p>Before 2026-04-21 the REST endpoint routed through the legacy
     * {@code InvoiceEconomicsUploadService.queueUploads / processUploads} voucher flow,
     * which broke after the 2026-04-16 PDF-refactor: both ISSUER and DEBTOR vouchers
     * failed on "No PDF available". Routing through {@code finalizeAutomatically} books
     * via Q2C (no local PDF needed) and the DEBTOR-side voucher fetches the PDF from
     * e-conomic via {@code EconomicsInvoiceService.loadInvoicePdfBytes}.
     *
     * @param invoiceUuid the UUID of a QUEUED INTERNAL / INTERNAL_SERVICE invoice
     * @return the finalized invoice (status = CREATED, economics_status = BOOKED
     *         if the DEBTOR side also succeeded; PARTIALLY_UPLOADED otherwise)
     * @throws NotFoundException   when the invoice does not exist
     * @throws BadRequestException when the invoice is not in QUEUED status, or is not
     *                             an INTERNAL / INTERNAL_SERVICE invoice
     */
    @Transactional
    public Invoice forceFinalizeQueued(String invoiceUuid) {
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceUuid));
        if (inv.getType() != InvoiceType.INTERNAL
                && inv.getType() != InvoiceType.INTERNAL_SERVICE) {
            throw new BadRequestException(
                    "Only INTERNAL or INTERNAL_SERVICE invoices can be force-created. "
                    + "Current type: " + inv.getType());
        }
        if (inv.getStatus() != InvoiceStatus.QUEUED) {
            throw new BadRequestException(
                    "Invoice must be in QUEUED status. Current: " + inv.getStatus());
        }
        // Mirror the nightly batchlet's date handling so manually and automatically
        // finalized invoices land on the same invoicedate/duedate convention.
        inv.setInvoicedate(LocalDate.now());
        inv.setDuedate(LocalDate.now().plusDays(1));
        return finalizeAutomatically(invoiceUuid);
    }

    /**
     * Recovery entry point for an INTERNAL / INTERNAL_SERVICE invoice stranded in
     * {@code PENDING_REVIEW}: an e-conomic draft exists but booking never ran (the
     * distribution page's finalize historically stopped after step 1).
     *
     * <p>Deliberately NOT a plain {@link #bookDraft}: the debtor-side voucher reads the
     * {@code @Transient} grandTotal set by the recalculator during {@code createDraft}, so
     * booking in a separate request would post a 0.00 DKK supplier voucher with no error
     * (see the single-transaction note on {@link #finalizeAutomatically}). Instead the
     * stranded e-conomic draft is cancelled and the invoice re-finalized from scratch,
     * which recreates the draft and books it with the grandTotal intact.
     *
     * <p>Failure modes: a cancel failure leaves the invoice PENDING_REVIEW, unchanged.
     * A finalize failure after a successful cancel rolls back to DRAFT — the grid then
     * shows the normal finalize action again.
     *
     * @param invoiceUuid the UUID of a PENDING_REVIEW INTERNAL / INTERNAL_SERVICE invoice
     * @return the finalized invoice (status = CREATED, economics_status = BOOKED if the
     *         DEBTOR side also succeeded; PARTIALLY_UPLOADED otherwise)
     * @throws NotFoundException   when the invoice does not exist
     * @throws BadRequestException when the invoice is not PENDING_REVIEW or not an
     *                             INTERNAL / INTERNAL_SERVICE invoice
     */
    // NOT @Transactional — cancelFinalization must COMMIT before finalizeAutomatically runs.
    // In a single transaction a finalize failure would roll the local row back to
    // PENDING_REVIEW while the e-conomic draft it references is already deleted.
    public Invoice refinalizePendingReview(String invoiceUuid) {
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceUuid));
        if (inv.getType() != InvoiceType.INTERNAL
                && inv.getType() != InvoiceType.INTERNAL_SERVICE) {
            throw new BadRequestException(
                    "Only INTERNAL or INTERNAL_SERVICE invoices can be re-finalized. "
                    + "Current type: " + inv.getType());
        }
        if (inv.getStatus() != InvoiceStatus.PENDING_REVIEW) {
            throw new BadRequestException(
                    "Invoice must be in PENDING_REVIEW status. Current: " + inv.getStatus());
        }
        log.infof("refinalizePendingReview: cancelling stranded e-conomic draft for %s "
                + "(draftNumber=%s) and re-finalizing", invoiceUuid, inv.getEconomicsDraftNumber());
        issuerSide.cancelFinalization(invoiceUuid); // tx1 — commits DRAFT, deletes the e-conomic draft
        return finalizeAutomatically(invoiceUuid);  // tx2 — fresh draft + book + debtor voucher
    }

    /**
     * Adopts a booking that e-conomic accepted but a local rollback discarded — the 2026-08-13
     * failure shape: {@code finalizeAutomatically} self-deadlocked recording Tx2, the local
     * transaction rolled back to DRAFT, and the vendor kept booked invoice N (evidence: the
     * outbox attempt row is PENDING with {@code posted_at} set, and the booked number is in the
     * {@code InvoiceBookVendorAccepted} log/metric).
     *
     * <p>The booked number cannot be discovered automatically (the vendor client has no filtered
     * list — see {@code InvoiceBookingAttemptWriter.markNeedsReconciliation}), so a human supplies
     * it from the logs or the e-conomic UI, and this method verifies it before adopting: the booked
     * invoice must exist in the issuer's agreement and its gross must match the gross derived from
     * this invoice's persisted items (±0.05).
     *
     * <p>On success: the attempt and the invoice row are durably marked booked
     * ({@code markBooked}, REQUIRES_NEW — safe here, no ambient transaction holds the row), the
     * entity is refreshed past its stale snapshot, and the debtor-side supplier voucher is posted
     * with the vendor-verified gross as {@code grandTotal} (failure demotes to
     * {@code PARTIALLY_UPLOADED}, same as the inline path).
     *
     * <p>Deliberately NOT {@code @Transactional}: an ambient transaction would pin REPEATABLE READ
     * snapshots that re-read the pre-adoption row state, and a failure-path persist would then
     * clobber the just-reconciled values.
     *
     * @param invoiceUuid  a DRAFT or PENDING_REVIEW INTERNAL / INTERNAL_SERVICE invoice
     * @param bookedNumber the booked invoice number at e-conomic, taken from the
     *                     {@code InvoiceBookVendorAccepted} log line or the vendor UI
     * @return the reconciled invoice (status CREATED)
     */
    public Invoice adoptVendorBooking(String invoiceUuid, int bookedNumber) {
        if (bookedNumber <= 0) {
            throw new BadRequestException("bookedNumber must be positive, got " + bookedNumber);
        }
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceUuid));
        if (inv.getType() != InvoiceType.INTERNAL
                && inv.getType() != InvoiceType.INTERNAL_SERVICE) {
            throw new BadRequestException(
                    "Only INTERNAL or INTERNAL_SERVICE invoices can adopt a vendor booking. "
                    + "Current type: " + inv.getType());
        }
        if (inv.getEconomicsBookedNumber() != null) {
            throw new BadRequestException("Invoice " + invoiceUuid
                    + " already carries economics_booked_number " + inv.getEconomicsBookedNumber());
        }

        // Evidence gate: adopting is only legal when an outbound booking POST actually happened.
        List<InvoiceBookingAttempt> posted = attemptRepo.listPostedUnresolvedByInvoice(invoiceUuid);
        if (posted.isEmpty()) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Invoice " + invoiceUuid + " has no posted, unresolved booking attempt — "
                            + "there is nothing to adopt. If the invoice was never booked at "
                            + "e-conomic, finalize it normally instead.")
                    .build());
        }
        InvoiceBookingAttempt attempt = posted.get(0);

        // Vendor verification: the booked invoice must exist in the ISSUER's agreement and its
        // gross must match what this invoice's persisted items would have produced.
        EconomicsAgreementResolver.Tokens tokens = agreements.tokens(inv.getCompany().getUuid());
        EconomicsBookedInvoice booked = bookApi.getBooked(
                tokens.appSecret(), tokens.agreementGrant(), bookedNumber);
        if (booked == null || booked.getGrossAmount() == null) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("e-conomic returned no gross amount for booked invoice " + bookedNumber
                            + " — cannot verify; not adopting.")
                    .build());
        }
        double expectedGross = expectedGrossFromItems(inv);
        if (Math.abs(booked.getGrossAmount() - expectedGross) > 0.05) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Booked invoice " + bookedNumber + " has gross " + booked.getGrossAmount()
                            + " but invoice " + invoiceUuid + " expects gross " + expectedGross
                            + " from its items — refusing to adopt a mismatched booking.")
                    .build());
        }

        log.warnf("adoptVendorBooking: adopting bookedNumber=%d for invoiceUuid=%s "
                        + "(attempt=%s, postedAt=%s, verified gross=%.2f)",
                bookedNumber, invoiceUuid, attempt.getUuid(), attempt.getPostedAt(),
                booked.getGrossAmount());

        // Durable write (attempt + invoice row) in its own committed transaction, then refresh the
        // entity past its stale pre-adoption snapshot before any further use.
        attemptWriter.markBooked(attempt.getUuid(), invoiceUuid, bookedNumber);
        invoices.refresh(inv);

        // Debtor-side supplier voucher with the vendor-verified gross. The transient grandTotal
        // must be set explicitly — this entity was never through createDraft's recalculation.
        inv.setGrandTotal(booked.getGrossAmount());
        issuerSide.postDebtorVoucherAfterReconcile(inv);

        log.infof("adoptVendorBooking: invoiceUuid=%s reconciled to bookedNumber=%d, "
                + "economicsStatus=%s", invoiceUuid, bookedNumber, inv.getEconomicsStatus());
        return inv;
    }

    /** Gross (VAT-inclusive) derived from persisted items: Σ(rate × hours) × (1 + vat%). */
    static double expectedGrossFromItems(Invoice inv) {
        double net = inv.invoiceitems == null ? 0.0
                : inv.invoiceitems.stream().mapToDouble(it -> it.rate * it.hours).sum();
        return BigDecimal.valueOf(net * (1 + inv.vat / 100.0))
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void assertInternalType(String invoiceUuid) {
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invoice not found: " + invoiceUuid));
        boolean internal = inv.getType() == InvoiceType.INTERNAL
                || inv.getType() == InvoiceType.INTERNAL_SERVICE;
        if (!internal && !inv.isInternalCreditNote()) {
            throw new IllegalArgumentException(
                    "InternalInvoiceOrchestrator only handles INTERNAL/INTERNAL_SERVICE invoices "
                    + "or internal CREDIT_NOTE reversals, got " + inv.getType() + " for " + invoiceUuid);
        }
    }
}

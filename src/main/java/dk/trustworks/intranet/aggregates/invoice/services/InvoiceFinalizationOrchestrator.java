package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.DraftContext;
import dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingRequest;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Owns the two-step e-conomic invoice finalization flow.
 *
 * <ol>
 *   <li>{@link #createDraft(String)} — posts the draft to Q2C v5.1.0, captures the
 *       draft number, sets status to PENDING_REVIEW. All step-1 side effects are
 *       reversible via {@link #cancelFinalization(String)}.</li>
 *   <li>{@link #bookDraft(String, String)} — books the draft via the legacy REST API,
 *       captures the booked invoice number, sets status to CREATED and
 *       economics_status to BOOKED. Calls registerAsPaidout (irreversible).</li>
 *   <li>{@link #cancelFinalization(String)} — deletes the draft (swallows 404), reverts
 *       status back to DRAFT, and reverses step-1 side effects.</li>
 * </ol>
 *
 * PHANTOM invoices are always rejected — they do not interact with e-conomic.
 * Breaks the bidirectional dependency between InvoiceService and
 * InvoiceAttributionService.
 *
 * SPEC-INV-001 §7.1, §7.2.
 */
@JBossLog
@ApplicationScoped
public class InvoiceFinalizationOrchestrator {

    @Inject
    InvoiceRepository invoices;

    @Inject
    BillingContextResolver billingResolver;

    @Inject
    EconomicsAgreementResolver agreements;

    @Inject
    InvoiceToEconomicsDraftMapper mapper;

    @Inject
    @RestClient
    EconomicsDraftInvoiceApiClient draftApi;

    @Inject
    @RestClient
    EconomicsBookingApiClient bookApi;

    @Inject
    InvoiceItemRecalculator recalc;

    @Inject
    BonusService bonus;

    @Inject
    InvoiceWorkService work;

    @Inject
    EconomicsInvoiceService economicsInvoiceService;

    @Inject
    DebtorCompanyLookup debtorCompanyLookup;

    @Inject
    EanPrerequisiteChecker eanChecker;

    /**
     * Step 1: Creates the e-conomic draft invoice.
     *
     * <p>Preconditions: invoice must be DRAFT or QUEUED; PHANTOM invoices are rejected.
     *
     * <p>Side effects (all reversible via cancelFinalization):
     * <ul>
     *   <li>Recalculates invoice items via PricingEngine.</li>
     *   <li>Recalculates bonus lines (skipped for INTERNAL / INTERNAL_SERVICE).</li>
     *   <li>Clears parent bonus fields for CREDIT_NOTE invoices.</li>
     *   <li>Sets economicsDraftNumber, billingClientUuid snapshot, status = PENDING_REVIEW.</li>
     * </ul>
     *
     * @param invoiceUuid the invoice to create a draft for.
     * @return the updated invoice entity.
     */
    @Transactional
    public Invoice createDraft(String invoiceUuid) {
        Invoice inv = requireEditableInvoice(invoiceUuid);

        if (inv.getType() == InvoiceType.PHANTOM) {
            throw new IllegalArgumentException(
                    "PHANTOM invoices do not interact with e-conomic: " + invoiceUuid);
        }

        // Step-1 reversible side effects — before the API call so a failure doesn't
        // leave the invoice in a partially mutated state
        recalc.recalculateInvoiceItems(inv);

        if (inv.getType() != InvoiceType.INTERNAL
                && inv.getType() != InvoiceType.INTERNAL_SERVICE) {
            bonus.recalcForInvoice(inv);
        }

        if (inv.getType() == InvoiceType.CREDIT_NOTE) {
            bonus.clearBonusFieldsOnParent(inv);
        }

        // Resolve billing context (contract + billing client)
        BillingContext bc = billingResolver.resolve(inv);
        String companyUuid = inv.getCompany().getUuid();
        EconomicsAgreementResolver.Tokens tokens = agreements.tokens(companyUuid);

        DraftContext ctx = new DraftContext(
                inv,
                bc.contract(),
                bc.billingClient(),
                agreements.layoutNumber(companyUuid),
                agreements.paymentTermFor(bc.contract()),
                agreements.vatZoneFor(inv.getCurrency(), companyUuid),
                agreements.productNumber(companyUuid)
        );

        // Build header payload and embed tw:uuid in otherReference for retry
        // reconciliation (SPEC-INV-001 §6.9)
        EconomicsDraftInvoice body = mapper.toDraft(ctx);
        body.setOtherReference(appendTwUuid(body.getOtherReference(), invoiceUuid));

        EconomicsDraftInvoice created = draftApi.create(
                tokens.appSecret(),
                tokens.agreementGrant(),
                "draft-" + invoiceUuid,
                body);

        draftApi.createLinesBulk(
                tokens.appSecret(),
                tokens.agreementGrant(),
                created.getDraftInvoiceNumber(),
                mapper.toLines(ctx));

        // Persist step-1 state
        inv.setEconomicsDraftNumber(created.getDraftInvoiceNumber());
        inv.setBillingClientUuid(bc.billingClient().getUuid());
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        invoices.persist(inv);

        log.infof("createDraft: invoiceUuid=%s draftNumber=%d",
                invoiceUuid, created.getDraftInvoiceNumber());
        return inv;
    }

    /**
     * Step 2: Books the e-conomic draft invoice.
     *
     * <p>Preconditions: invoice must be PENDING_REVIEW with a draft number set.
     *
     * <p>Side effects (irreversible after persist):
     * <ul>
     *   <li>Sets economicsBookedNumber, invoicenumber, status = CREATED,
     *       economicsStatus = BOOKED.</li>
     *   <li>Calls registerAsPaidout on all work items (irreversible).</li>
     * </ul>
     *
     * @param invoiceUuid the invoice to book.
     * @param sendBy      optional delivery method — null | "ean" | "Email".
     * @return the updated invoice entity.
     */
    @Transactional
    public Invoice bookDraft(String invoiceUuid, String sendBy) {
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invoice not found: " + invoiceUuid));

        if (inv.getStatus() != InvoiceStatus.PENDING_REVIEW) {
            throw new IllegalStateException(
                    "Invoice " + invoiceUuid + " is not in PENDING_REVIEW (status="
                    + inv.getStatus() + ")");
        }
        if (inv.getEconomicsDraftNumber() == null) {
            throw new IllegalStateException(
                    "Invoice " + invoiceUuid + " has no economicsDraftNumber — cannot book");
        }

        // Normalize and validate sendBy (SPEC-INV-001 §4.2, §4.3, §6.6)
        if ("ean".equalsIgnoreCase(sendBy)) {
            BillingContext bc = billingResolver.resolve(inv);
            EanPrerequisiteErrorDto err = eanChecker.check(bc);
            if (err != null) {
                throw new EanPrerequisitesNotMet(err);
            }
            sendBy = "ean";  // canonical lowercase per SPEC §6.6
        } else if ("email".equalsIgnoreCase(sendBy)) {
            sendBy = "Email";  // canonical capitalised per SPEC §6.6
        } else if (sendBy != null && !sendBy.isBlank()) {
            throw new IllegalArgumentException("Unsupported sendBy: " + sendBy);
        } else {
            sendBy = null;  // omit entirely from JSON
        }

        String companyUuid = inv.getCompany().getUuid();
        EconomicsAgreementResolver.Tokens tokens = agreements.tokens(companyUuid);

        EconomicsBookingRequest req = EconomicsBookingRequest.of(
                inv.getEconomicsDraftNumber(), sendBy);

        EconomicsBookedInvoice booked = bookApi.book(
                tokens.appSecret(),
                tokens.agreementGrant(),
                "book-" + invoiceUuid,
                req);

        inv.setEconomicsBookedNumber(booked.getBookedInvoiceNumber());
        inv.setInvoicenumber(booked.getBookedInvoiceNumber());
        inv.setStatus(InvoiceStatus.CREATED);
        inv.setEconomicsStatus(EconomicsInvoiceStatus.BOOKED);
        inv.setSendBy(sendBy);  // persist delivery method for E-Invoicing tracking
        invoices.persist(inv);

        // Irreversible step-2 side effect. E-conomic has already booked the
        // invoice at this point, so a RuntimeException from registerAsPaidout
        // MUST NOT roll back the booked/CREATED state we just persisted —
        // doing so would leave the DB out-of-sync with e-conomic and every
        // subsequent book attempt would fight the booking idempotency cache.
        // Log loudly so ops can reconcile work-item payout status manually.
        try {
            work.registerAsPaidout(inv);
        } catch (RuntimeException e) {
            log.errorf(e, "bookDraft: registerAsPaidout failed AFTER e-conomic booked invoice %s "
                    + "(bookedNumber=%d) — manual work-item reconciliation required",
                    invoiceUuid, booked.getBookedInvoiceNumber());
        }

        // DEBTOR-side voucher for internal invoices (SPEC-INV-001 §4.5, §4.7, §10).
        // The issuer side already went through the standard Q2C path above.  Now post
        // a supplier-invoice entry to the debtor company's e-conomic journal so their
        // books reflect the inter-company purchase.
        //
        // Negative grandTotal values (credit notes) flow through unchanged — the mapper
        // already produces negative amounts on the issuer side (H5), and
        // EconomicsInvoiceService.sendVoucherToCompany uses getGrandTotal() directly,
        // producing a supplier credit on the debtor side.
        //
        // A failure here sets economics_status = PARTIALLY_UPLOADED rather than
        // propagating the exception; the retry batchlet (H12) will re-attempt.
        if (inv.getType() == InvoiceType.INTERNAL
                || inv.getType() == InvoiceType.INTERNAL_SERVICE) {
            postDebtorSideVoucher(inv);
        }

        log.infof("bookDraft: invoiceUuid=%s bookedNumber=%d",
                invoiceUuid, booked.getBookedInvoiceNumber());
        return inv;
    }

    /**
     * Posts a supplier-invoice entry to the debtor company's e-conomic journal.
     *
     * <p>Failures are caught and demoted to a {@code PARTIALLY_UPLOADED} status so
     * the caller's transaction can still commit and the retry batchlet can recover.
     *
     * <p>Uses {@link DebtorCompanyLookup} and {@link EconomicsAgreementResolver} instead of
     * Panache static methods so this path is fully unit-testable without a live DB session.
     *
     * @param inv the internal invoice that was just booked on the issuer side
     */
    private void postDebtorSideVoucher(Invoice inv) {
        if (inv.getDebtorCompanyuuid() == null || inv.getDebtorCompanyuuid().isBlank()) {
            log.warnf("bookDraft: INTERNAL invoice %s has no debtorCompanyuuid — "
                    + "skipping DEBTOR-side voucher post", inv.getUuid());
            return;
        }

        try {
            Company debtorCompany = debtorCompanyLookup.findByUuid(inv.getDebtorCompanyuuid())
                    .orElseThrow(() -> new IllegalStateException(
                            "Debtor company not found: " + inv.getDebtorCompanyuuid()));

            int journalNumber = agreements.internalJournalNumber(inv.getDebtorCompanyuuid());

            try (jakarta.ws.rs.core.Response response =
                         economicsInvoiceService.sendVoucherToCompany(inv, debtorCompany, journalNumber)) {
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    String body = response.readEntity(String.class);
                    throw new RuntimeException(
                            "DEBTOR-side voucher post returned HTTP " + response.getStatus()
                            + " for invoice " + inv.getUuid() + ": " + body);
                }
            }
            log.infof("bookDraft: DEBTOR-side voucher posted for internal invoice %s to company %s",
                    inv.getUuid(), debtorCompany.getName());
        } catch (Exception e) {
            log.warnf(e, "bookDraft: DEBTOR-side voucher post failed for internal invoice %s — "
                    + "setting PARTIALLY_UPLOADED for retry", inv.getUuid());
            inv.setEconomicsStatus(EconomicsInvoiceStatus.PARTIALLY_UPLOADED);
            invoices.persist(inv);
        }
    }

    /**
     * Cancels step 1: deletes the draft from e-conomic and reverts the invoice to DRAFT.
     *
     * <p>Precondition: invoice must be PENDING_REVIEW.
     *
     * <p>404 responses from the draft DELETE are swallowed (idempotent cancel).
     * Any other error propagates.
     *
     * <p>Reverses step-1 side effects:
     * <ul>
     *   <li>Clears economicsDraftNumber, reverts status to DRAFT.</li>
     *   <li>Calls restoreParentBonusFields for CREDIT_NOTE invoices.</li>
     * </ul>
     *
     * @param invoiceUuid the invoice to cancel finalization for.
     * @return the updated invoice entity.
     */
    @Transactional
    public Invoice cancelFinalization(String invoiceUuid) {
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invoice not found: " + invoiceUuid));

        if (inv.getStatus() != InvoiceStatus.PENDING_REVIEW) {
            throw new IllegalStateException(
                    "Invoice " + invoiceUuid + " is not in PENDING_REVIEW (status="
                    + inv.getStatus() + ")");
        }

        if (inv.getEconomicsDraftNumber() != null) {
            String companyUuid = inv.getCompany().getUuid();
            EconomicsAgreementResolver.Tokens tokens = agreements.tokens(companyUuid);
            try {
                draftApi.delete(
                        tokens.appSecret(),
                        tokens.agreementGrant(),
                        inv.getEconomicsDraftNumber());
            } catch (Exception e) {
                if (!isNotFound(e)) {
                    throw e;
                }
                log.infof("cancelFinalization: draft %d already deleted (404 swallowed) for invoice=%s",
                        inv.getEconomicsDraftNumber(), invoiceUuid);
            }
        }

        inv.setEconomicsDraftNumber(null);
        // Snapshot was set in createDraft — clear it so cancelFinalization
        // truly reverses every step-1 persist.
        inv.setBillingClientUuid(null);
        inv.setStatus(InvoiceStatus.DRAFT);
        invoices.persist(inv);

        // Reverse step-1 reversible side effects
        if (inv.getType() == InvoiceType.CREDIT_NOTE) {
            bonus.restoreParentBonusFields(inv);
        }

        log.infof("cancelFinalization: invoiceUuid=%s reverted to DRAFT", invoiceUuid);
        return inv;
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private Invoice requireEditableInvoice(String uuid) {
        Invoice inv = invoices.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invoice not found: " + uuid));
        if (inv.getStatus() != InvoiceStatus.DRAFT
                && inv.getStatus() != InvoiceStatus.QUEUED) {
            throw new IllegalStateException(
                    "Invoice " + uuid + " is not DRAFT/QUEUED (status=" + inv.getStatus() + ")");
        }
        return inv;
    }

    private static String appendTwUuid(String currentRef, String invoiceUuid) {
        String tag = "tw:" + invoiceUuid;
        if (currentRef == null || currentRef.isBlank()) {
            return tag;
        }
        return currentRef + " | " + tag;
    }

    private static boolean isNotFound(Throwable e) {
        // MP REST Client surfaces HTTP errors as WebApplicationException with the
        // response attached. Read the status code — string-matching "404" in the
        // message is fragile (see SPEC-INV-001 review findings).
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof WebApplicationException wae) {
                Response response = wae.getResponse();
                if (response != null && response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                    return true;
                }
            }
            if (cursor.getCause() == cursor) break;
            cursor = cursor.getCause();
        }
        return false;
    }
}

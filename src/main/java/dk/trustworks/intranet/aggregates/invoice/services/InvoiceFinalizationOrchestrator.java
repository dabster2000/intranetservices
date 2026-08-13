package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
import dk.trustworks.intranet.aggregates.invoice.economics.DraftContext;
import dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingRequest;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttempt;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptWriter;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.DraftInvoiceLineBatchRequest;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.exceptions.DatabaseConstraintViolationExceptionMapper;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.perf.PerfMetrics;
import dk.trustworks.intranet.perf.PerfTimed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
    InvoiceAttributionService attributionService;

    @Inject
    BonusService bonus;

    @Inject
    jakarta.enterprise.event.Event<InvoiceBookedEvent> invoiceBooked;

    @Inject
    EconomicsInvoiceService economicsInvoiceService;

    @Inject
    DebtorCompanyLookup debtorCompanyLookup;

    @Inject
    EanPrerequisiteChecker eanChecker;

    @Inject
    CreditNoteCoverageService creditCoverage;

    @Inject
    PerfMetrics perfMetrics;

    /** Tx1/Tx2 of the booking outbox. Must be a separate bean — REQUIRES_NEW needs the CDI proxy. */
    @Inject
    InvoiceBookingAttemptWriter attempts;

    @Inject
    InvoiceBookingAttemptRepository attemptRepo;

    @Inject
    jakarta.transaction.TransactionManager transactionManager;

    /**
     * Kill switch for internal-invoice e-conomic writes. Staging sets this {@code false}
     * so it can never create drafts or book invoices in the shared production e-conomic
     * (2026-06-16 duplicate incident). Covers every booking path: nightly batch first &
     * settlement passes, manual force-create-queued, and the upload retry job.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "dk.trustworks.invoice.economics-upload.enabled", defaultValue = "true")
    boolean invoiceUploadEnabled;

    /**
     * Credit-note finalization guard (framework-agreements redesign §9.6). When {@code true},
     * {@link #createDraft(String)} skips invoice-item recalculation for CREDIT_NOTE invoices so
     * the lines copied from the source invoice (BASE + CALCULATED, see
     * {@link InvoiceService#createCreditNote}) are preserved verbatim — a credit note should
     * credit what was BILLED, not what today's pricing rules say. Default {@code false} keeps
     * the current behavior (re-run the pricing engine) byte-identical.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "feature.invoice.cn-preserve-lines", defaultValue = "false")
    boolean cnPreserveLines;

    /**
     * Step 1: Creates the e-conomic draft invoice.
     *
     * <p>Preconditions: invoice must be DRAFT or QUEUED; PHANTOM invoices are rejected.
     *
     * <p>Side effects (all reversible via cancelFinalization):
     * <ul>
     *   <li>Recalculates invoice items via PricingEngine (skipped for CREDIT_NOTE
     *       invoices when {@code feature.invoice.cn-preserve-lines} is enabled).</li>
     *   <li>Recalculates bonus lines (skipped for INTERNAL / INTERNAL_SERVICE).</li>
     *   <li>Clears parent bonus fields for CREDIT_NOTE invoices.</li>
     *   <li>Sets economicsDraftNumber, billingClientUuid snapshot, status = PENDING_REVIEW.</li>
     * </ul>
     *
     * @param invoiceUuid the invoice to create a draft for.
     * @return the updated invoice entity.
     */
    @PerfTimed("invoice.createDraft")
    @Transactional
    public Invoice createDraft(String invoiceUuid) {
        if (!invoiceUploadEnabled) {
            throw new BadRequestException(
                    "Invoice e-conomic upload is disabled (dk.trustworks.invoice.economics-upload.enabled=false)");
        }
        Invoice inv = requireEditableInvoice(invoiceUuid);

        if (inv.getType() == InvoiceType.PHANTOM) {
            throw new BadRequestException(
                    "PHANTOM invoices do not interact with e-conomic: " + invoiceUuid);
        }

        // Step-1 reversible side effects — before the API call so a failure doesn't
        // leave the invoice in a partially mutated state
        if (cnPreserveLines && inv.getType() == InvoiceType.CREDIT_NOTE) {
            // §9.6 CN guard: credit what was billed, not what today's rules say.
            log.infof("createDraft: feature.invoice.cn-preserve-lines=true — skipping item "
                    + "recalculation for CREDIT_NOTE %s (preserving lines copied from the source invoice)",
                    invoiceUuid);
        } else {
            recalc.recalculateInvoiceItems(inv);
        }

        // e-conomic's Q2C bulk-lines endpoint rejects any line with quantity 0
        // (SalesDocumentLineCannotHaveZeroValue) and fails the WHOLE batch, so a single
        // 0-hours line turns draft creation into an opaque HTTP 400 (2026-07-01 prod
        // incident). Detect it here — after recalculation, on exactly the items the
        // mapper will send — and fail fast with an actionable message before any
        // e-conomic call, which also avoids leaving an orphaned empty draft behind.
        assertNoZeroQuantityLines(inv);

        // Over-credit guard for client credit notes (multiple partial CNs per invoice are
        // allowed since V386): inside this transaction, with a pessimistic lock on the source
        // row, so two concurrently finalizing credit notes can never jointly exceed the
        // source amount — the second finalizer gets a 409. Runs after recalculation so the
        // guard sees exactly the amounts that will be posted. No-op for internal credit
        // notes (strict 1:1, enforced at creation) and non-credit-note invoices.
        creditCoverage.assertFinalizableWithinResidual(inv);

        if (inv.getType() != InvoiceType.INTERNAL
                && inv.getType() != InvoiceType.INTERNAL_SERVICE) {
            bonus.recalcForInvoice(inv);
        }

        if (inv.getType() == InvoiceType.CREDIT_NOTE) {
            bonus.clearBonusFieldsOnParent(inv);
        }

        // Rebuild attributions using the AI-powered resolve pipeline so the
        // review modal reflects AI-refined splits. Falls back to deterministic
        // resolution inside the pipeline when the AI path is unavailable. Runs
        // before the e-conomic API calls so the persisted draft and the
        // attributions are consistent.
        try {
            attributionService.resolveAndPersistAttributions(invoiceUuid);
        } catch (Exception e) {
            log.warnf(e, "createDraft: AI attribution resolve failed for %s — falling back to deterministic compute", invoiceUuid);
            attributionService.computeAttributions(invoiceUuid);
        }

        // Attribute the non-consultant discount/fee lines (null-consultant BASE + CALCULATED)
        // that the resolve pipeline skips, so they flow into internal invoices without a manual
        // "Recompute attributions". Idempotent (no-op if the fallback above already attributed
        // them) and best-effort: a failure here must not block draft creation.
        try {
            attributionService.attributeNonConsultantLines(invoiceUuid);
        } catch (Exception e) {
            log.warnf(e, "createDraft: non-consultant attribution pass failed for %s", invoiceUuid);
        }

        // Resolve billing context (contract + billing client)
        BillingContext bc = billingResolver.resolve(inv);
        String companyUuid = inv.getCompany().getUuid();
        EconomicsAgreementResolver.Tokens tokens = agreements.tokens(companyUuid);

        // INTERNAL / INTERNAL_SERVICE invoices are issued only after the external
        // client has paid, so the intermediary settles immediately. Their payment
        // term must also be valid in the ISSUER's own e-conomic agreement — the
        // debtor contract's term number frequently is not (subsidiaries carry only
        // the low numbers while the debtor uses e.g. 8 = "Netto 30 dage"), which
        // otherwise makes e-conomic reject the draft with HTTP 400. Regular invoices
        // keep the contract's payment term.
        boolean internal = inv.getType() == InvoiceType.INTERNAL
                || inv.getType() == InvoiceType.INTERNAL_SERVICE
                || inv.isInternalCreditNote();
        int termOfPaymentNumber = internal
                ? agreements.immediatePaymentTermFor(companyUuid)
                : agreements.paymentTermFor(bc.contract());

        DraftContext ctx = new DraftContext(
                inv,
                bc.contract(),
                bc.billingClient(),
                agreements.layoutNumber(companyUuid),
                termOfPaymentNumber,
                agreements.vatZoneFor(inv.getCurrency(), companyUuid),
                agreements.productNumber(companyUuid)
        );

        EconomicsDraftInvoice body = mapper.toDraft(ctx);

        // Idempotency-Key must be unique per attempt. e-conomic's idempotency layer caches the
        // response for ONE HOUR (not the ~24h an earlier version of this comment claimed — the
        // vendor doc says 1h, and production confirmed it on 2026-08-07: the same key was refused
        // at T+54min and accepted at T+2h21m, booking a second invoice). A fixed
        // "draft-{invoiceUuid}" key would replay the deleted-draft's number after a
        // cancelFinalization, then the subsequent createLinesBulk 404s on that recycled (deleted)
        // draft. UUID suffix forces a fresh attempt each time.
        //
        // The booking path deliberately does NOT do this — see reserveBookingAttempt. Its key must
        // stay invariant so that during a blue/green rollout an old task and a new task still
        // collide at the vendor instead of producing two booked invoices.
        String idempotencyKey = "draft-" + invoiceUuid + "-" + UUID.randomUUID();
        log.infof("createDraft: invoiceUuid=%s idempotencyKey=%s", invoiceUuid, idempotencyKey);

        CreatedResult created = draftApi.create(
                tokens.appSecret(),
                tokens.agreementGrant(),
                idempotencyKey,
                body);
        Integer draftNumber = Objects.requireNonNull(created.getNumber(),
                "e-conomic POST /invoices/drafts returned no number");

        draftApi.createLinesBulk(
                tokens.appSecret(),
                tokens.agreementGrant(),
                draftNumber,
                new DraftInvoiceLineBatchRequest(mapper.toLines(ctx)));

        // Persist step-1 state
        inv.setEconomicsDraftNumber(draftNumber);
        // Stamp the billing client only if not already stamped at creation time.
        // INTERNAL / INTERNAL_SERVICE invoices are stamped by InvoiceService with
        // the intercompany Client (see IntercompanyClientResolver); overwriting
        // here would clobber that mapping with the contract's external client.
        // Regular INVOICE flow still gets stamped here for the first time.
        if (inv.getBillingClientUuid() == null || inv.getBillingClientUuid().isBlank()) {
            inv.setBillingClientUuid(bc.billingClient().getUuid());
        }
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        invoices.persist(inv);

        // Flush HERE, not at JTA commit. MariaDB raises 1213 at the statement that closes the lock
        // cycle, and by default that statement is issued during Hibernate's auto-flush inside
        // beforeCompletion — after this method has already returned. There the failure surfaces as
        // a CHECKED RollbackException that Arc rewraps as ArcUndeclaredThrowableException, which is
        // how the 2026-08-07 deadlock became an opaque 500. Forcing the flush inside the method
        // turns it into an unchecked PersistenceException on this frame instead, which:
        //   - DatabaseConstraintViolationExceptionMapper maps directly to a retriable 409, and
        //   - PerfTimedInterceptor (@Priority 2050, INSIDE the tx interceptor at 200) actually
        //     sees, so `invoice.createDraft` emits outcome=error rather than a success 1.0s before
        //     the rollback.
        // Same precedent and same reasoning as WorkService.java:600-604, CareerLevelService.java:103-107
        // and FinanceLoadJob.java:150-174.
        //
        // NOT done at the end of bookDraft, deliberately — see the note on that method.
        invoices.flush();

        log.infof("createDraft: invoiceUuid=%s draftNumber=%d",
                invoiceUuid, draftNumber);
        return inv;
    }

    /**
     * Rejects finalization when any invoice line has a 0 quantity (hours).
     *
     * <p>{@link InvoiceToEconomicsDraftMapper#toLines} maps {@code quantity =
     * item.getHours()}, and e-conomic's Q2C {@code POST /invoices/drafts/{n}/lines/bulk}
     * rejects a 0-quantity line with {@code SalesDocumentLineCannotHaveZeroValue},
     * failing the entire batch. Surfacing a specific, actionable error here beats the
     * opaque "HTTP 400 Bad Request" the user would otherwise see, and failing before
     * {@code draftApi.create} avoids leaving an orphaned empty draft in e-conomic.
     */
    private void assertNoZeroQuantityLines(Invoice inv) {
        List<InvoiceItem> items = inv.getInvoiceitems();
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> offending = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            InvoiceItem item = items.get(i);
            if (item.getHours() == 0d) {
                offending.add("line " + (i + 1) + " \"" + describeLine(item) + "\"");
            }
        }
        if (!offending.isEmpty()) {
            throw new BadRequestException(
                    "Cannot finalize invoice " + inv.getUuid() + ": e-conomic rejects invoice "
                    + "lines with 0 hours. Enter an hours/quantity value or remove "
                    + (offending.size() == 1 ? "this line: " : "these lines: ")
                    + String.join(", ", offending) + ".");
        }
    }

    /** Best-effort human label for an invoice line, for error messages. */
    private static String describeLine(InvoiceItem item) {
        if (item.getItemname() != null && !item.getItemname().isBlank()) {
            return item.getItemname();
        }
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            return item.getDescription();
        }
        return "(no description)";
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
     * <p><b>No {@code flush()} at the end of this method</b>, unlike {@link #createDraft}. The
     * Fix-A flush belongs where an in-method flush can still change the outcome, and here it
     * cannot: this method is no longer {@code @Transactional}, so on the interactive path there is
     * no transaction of ours to flush and {@code em.flush()} would simply throw
     * {@code TransactionRequiredException} on every booking. The durable write is
     * {@code InvoiceBookingAttemptWriter.markBooked}, which commits in its own {@code REQUIRES_NEW}
     * transaction before this method returns; a deadlock there already surfaces on this frame and
     * is retried once (see below). On the one path that still has an ambient transaction
     * ({@code InternalInvoiceOrchestrator.finalizeAutomatically}) a flush here would only pull an
     * unrelated caller's dirty entities forward, and the booked state is already committed by then.
     *
     * @param invoiceUuid the invoice to book.
     * @param sendBy      optional delivery method — null | "ean" | "Email".
     * @return the updated invoice entity.
     */
    // NOT A SUCCESS SIGNAL. @PerfTimed stays because the latency measurement is genuinely useful,
    // but `OperationCount{phase=invoice.book, outcome=success}` MUST NEVER carry an alarm.
    // PerfTimedInterceptor is @Priority(APPLICATION + 50) = 2050 and Quarkus'
    // TransactionalInterceptorRequired is @Priority(200); lower priority is outer, so PerfTimed
    // runs INSIDE the transaction and emits before the commit. On 2026-08-07 it wrote
    // {"phase":"invoice.book","outcome":"success"} at 08:46:21.766 and the deadlock that rolled the
    // transaction back was logged at 08:46:22.797 — a success metric 1.031 s ahead of the failure,
    // and no outcome=error was ever emitted for that attempt.
    // S1 removed this method's own @Transactional, which fixes the interactive path, but
    // InternalInvoiceOrchestrator.finalizeAutomatically still wraps it in one (deliberately — see
    // R1/the @Transient grandTotal note), so the lie survives on the internal-invoice path.
    // The booking-success signals are the outbox row (state=BOOKED), the "COMMITTED" log line and
    // the InvoiceBookCommitted metric emitted below — all of which follow Tx2's commit.
    @PerfTimed("invoice.book")
    public Invoice bookDraft(String invoiceUuid, String sendBy) {
        if (!invoiceUploadEnabled) {
            throw new BadRequestException(
                    "Invoice e-conomic upload is disabled (dk.trustworks.invoice.economics-upload.enabled=false)");
        }
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new NotFoundException(
                        "Invoice not found: " + invoiceUuid));

        if (inv.getStatus() != InvoiceStatus.PENDING_REVIEW) {
            throw new BadRequestException(
                    "Invoice " + invoiceUuid + " is not in PENDING_REVIEW (status="
                    + inv.getStatus() + ")");
        }
        if (inv.getEconomicsDraftNumber() == null) {
            throw new BadRequestException(
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
            throw new BadRequestException("Unsupported sendBy: " + sendBy);
        } else {
            sendBy = null;  // omit entirely from JSON
        }

        String companyUuid = inv.getCompany().getUuid();
        EconomicsAgreementResolver.Tokens tokens = agreements.tokens(companyUuid);

        // ── Outbox: reserve → POST → record ────────────────────────────────────────
        //
        // The POST below is IRREVERSIBLE and must not sit inside a transaction of ours. On
        // 2026-08-07 it succeeded (bookedNumber=28214) and the enclosing transaction then died
        // on a MariaDB 1213 deadlock at commit, discarding the local record of a booking that
        // had really happened. Four retries re-resolved draftInvoiceNumber live, changing the
        // payload under an unchanged Idempotency-Key, and e-conomic answered 400 PayloadChanged.
        // Once its one-hour window lapsed a further attempt booked a SECOND invoice, 28218.
        //
        // So: freeze the key and the body in a committed row first, POST outside any transaction
        // of ours, and record the outcome in a second committed transaction.
        InvoiceBookingAttempt attempt = reserveBookingAttempt(invoiceUuid, companyUuid, tokens, inv, sendBy);

        if (attempt.getState() == InvoiceBookingAttempt.State.BOOKED) {
            // Already booked — by an earlier attempt, or by another task. Never POST again.
            log.infof("bookDraft: attempt %s already BOOKED (bookedNumber=%d) for invoiceUuid=%s "
                            + "— reconciling locally, no outbound call",
                    attempt.getUuid(), attempt.getBookedNumber(), invoiceUuid);
            return applyBookedState(inv, attempt.getBookedNumber(), attempt.getSendBy(), invoiceUuid,
                    attempt.getIdempotencyKey(), false);
        }
        if (attempt.getState() == InvoiceBookingAttempt.State.NEEDS_RECONCILIATION) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Invoice " + invoiceUuid + " has a booking attempt awaiting manual "
                            + "reconciliation (attempt " + attempt.getUuid() + "). Its outcome at "
                            + "e-conomic is unknown — resolve it there before retrying.")
                    .build());
        }
        if (!attempt.isReplaySafe()) {
            // Past e-conomic's idempotency window a replay is no longer deduplicated: the same key
            // and body would create a second booked invoice. This is exactly how 28218 happened.
            attempts.markNeedsReconciliation(attempt.getUuid(),
                    "Idempotency window expired (postedAt=" + attempt.getPostedAt()
                            + "); replaying would risk a duplicate booking");
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Invoice " + invoiceUuid + " was posted to e-conomic at "
                            + attempt.getPostedAt() + " and its idempotency window has expired. "
                            + "Check e-conomic for a booked invoice before retrying — booking again "
                            + "now would create a duplicate.")
                    .build());
        }

        // Rebuild the body from the frozen primitives and prove it is byte-identical to what we
        // committed. A mismatch means our own serialisation drifted; POSTing then would repeat the
        // PayloadChanged failure, so refuse instead.
        EconomicsBookingRequest req = EconomicsBookingRequest.of(
                attempt.getDraftInvoiceNumber(), attempt.getSendBy());
        String replayJson = attempts.serialise(req);
        if (!InvoiceBookingAttemptWriter.sha256(replayJson).equals(attempt.getPayloadHash())) {
            attempts.markNeedsReconciliation(attempt.getUuid(),
                    "Replay payload diverged from the frozen body: " + replayJson);
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Booking payload for invoice " + invoiceUuid + " no longer matches the "
                            + "frozen request body — refusing to POST.")
                    .build());
        }

        attempts.markPosted(attempt.getUuid());
        log.infof("bookDraft: POSTing invoiceUuid=%s attempt=%s idempotencyKey=%s "
                        + "draftInvoiceNumber=%d sendBy=%s",
                invoiceUuid, attempt.getUuid(), attempt.getIdempotencyKey(),
                attempt.getDraftInvoiceNumber(), attempt.getSendBy());

        EconomicsBookedInvoice booked;
        try {
            booked = bookApi.book(
                    tokens.appSecret(),
                    tokens.agreementGrant(),
                    attempt.getIdempotencyKey(),
                    req);
        } catch (WebApplicationException e) {
            throw classifyBookingFailure(attempt, invoiceUuid, e);
        } catch (RuntimeException e) {
            // No HTTP response reached us, so we cannot know whether e-conomic booked it.
            // Fail closed: never retry blindly (P3).
            attempts.markNeedsReconciliation(attempt.getUuid(),
                    "No response from e-conomic: " + e);
            throw e;
        }

        log.infof("bookDraft: e-conomic ACCEPTED invoiceUuid=%s idempotencyKey=%s bookedNumber=%d "
                        + "— NOT YET COMMITTED LOCALLY",
                invoiceUuid, attempt.getIdempotencyKey(), booked.getBookedInvoiceNumber());

        // Emitted from the NON-transactional segment, between the vendor's 201 and Tx2, so it
        // cannot be rolled back by anything downstream. This is the numerator of the split-brain
        // invariant: InvoiceBookVendorAccepted == InvoiceBookCommitted + (orphans). A gap between
        // the two counters is the 2026-08-07 signature, and it is the only signal that survives the
        // JVM dying between the 201 and the commit — which is exactly what an ECS task replacement
        // mid-rollout does.
        // Cardinality rule (PerfMetrics:14-18): identifiers go in the FIELDS map, never in the
        // dimension map, so the metric stays one time series and stays Logs-Insights-queryable.
        emitBookingCounter("InvoiceBookVendorAccepted", invoiceUuid, attempt.getIdempotencyKey(),
                booked.getBookedInvoiceNumber());

        // Tx2 — commits alone. From here the booking is durably recorded even if anything below fails.
        //
        // Bounded retry: EXACTLY ONE extra attempt, and ONLY here. Losing Tx2 *is* the split-brain,
        // and this segment has no external side effect, so replaying it is safe in a way that
        // replaying the POST above is not (P3). `attempts` is a different bean, so every call goes
        // through the CDI proxy and REQUIRES_NEW genuinely opens a fresh transaction — a `this.`
        // re-entry would self-invoke past the interceptor and reuse the doomed session.
        // markBooked is idempotent: the attempt row converges on BOOKED and the invoice update
        // carries `WHERE economics_booked_number IS NULL`, so a redundant run writes nothing.
        // Ambient-transaction split (2026-08-13, invoice 603bdb0d): on the finalizeAutomatically
        // path the outer transaction already holds this invoice row's X-lock (createDraft flushed
        // a status update), so markBooked's REQUIRES_NEW invoices-row update would wait on our own
        // suspended transaction — a guaranteed self-deadlock that burned 2× innodb_lock_wait_timeout
        // and rolled back a vendor-accepted booking. With an ambient transaction, record only the
        // attempt row durably (markBookedAttemptOnly); the outer transaction persists the invoice
        // state itself via applyBookedState's managed-entity mutations at commit. Without one
        // (interactive /book path), keep the combined durable write — there the bulk invoices-row
        // update is the ONLY writer, since entity mutations outside a transaction never flush.
        boolean ambientTx = hasActiveTransaction();
        try {
            if (ambientTx) {
                attempts.markBookedAttemptOnly(attempt.getUuid(), booked.getBookedInvoiceNumber());
            } else {
                attempts.markBooked(attempt.getUuid(), invoiceUuid, booked.getBookedInvoiceNumber());
            }
        } catch (RuntimeException e) {
            // Arc rewraps the checked commit-phase RollbackException as ArcUndeclaredThrowableException,
            // so the catch has to be RuntimeException and the test has to be shape-independent.
            if (!DatabaseConstraintViolationExceptionMapper.isTransientLockContention(e)) throw e;
            log.warnf(e, "bookDraft: lock contention recording Tx2 for invoiceUuid=%s attempt=%s "
                            + "bookedNumber=%d — retrying ONCE in a fresh transaction. e-conomic has "
                            + "already booked this invoice; the POST is NOT repeated.",
                    invoiceUuid, attempt.getUuid(), booked.getBookedInvoiceNumber());
            // A second failure propagates: the attempt stays PENDING with posted_at set, so the next
            // bookDraft replays the same key and the same frozen body inside the TTL and e-conomic
            // returns its cached 201. That is the designed recovery, not a silent loss.
            if (ambientTx) {
                attempts.markBookedAttemptOnly(attempt.getUuid(), booked.getBookedInvoiceNumber());
            } else {
                attempts.markBooked(attempt.getUuid(), invoiceUuid, booked.getBookedInvoiceNumber());
            }
        }

        emitBookingCounter("InvoiceBookCommitted", invoiceUuid, attempt.getIdempotencyKey(),
                booked.getBookedInvoiceNumber());

        return applyBookedState(inv, booked.getBookedInvoiceNumber(), sendBy, invoiceUuid,
                attempt.getIdempotencyKey(), true);
    }

    /**
     * Emits one booking-lifecycle counter. Identifiers travel as EMF <em>fields</em>, never as
     * dimensions, so the metric stays a single bounded time series while still being queryable per
     * invoice in Logs Insights (PerfMetrics:14-18, :50 prepends the {@code env} dimension).
     */
    private void emitBookingCounter(String metricName, String invoiceUuid,
                                    String idempotencyKey, Integer bookedNumber) {
        perfMetrics.emitCount(metricName, 1, java.util.Map.of(), java.util.Map.of(
                "invoiceUuid", String.valueOf(invoiceUuid),
                "idempotencyKey", String.valueOf(idempotencyKey),
                "bookedNumber", String.valueOf(bookedNumber)));
    }

    /**
     * Reconciles the in-memory invoice with a booking that e-conomic has already accepted, then
     * runs the post-booking side effects.
     *
     * <p>The booked state itself was persisted by {@code InvoiceBookingAttemptWriter.markBooked}
     * in its own committed transaction. The mutations here are deliberately <b>in-memory only</b>:
     * {@code postDebtorSideVoucher} reads {@code getGrandTotal()} — a {@code @Transient} field set
     * by the recalculator — and {@code getInvoicenumber()}, both with silent fallbacks. Reloading
     * the entity instead of mutating this instance posts a 0.00 intercompany voucher and reports
     * no error. See the single-transaction note at {@code InvoiceResource:378-381}.
     *
     * @param freshBooking false when short-circuiting an attempt that was already BOOKED, in which
     *                     case the debtor-side voucher is skipped — it is not idempotent, and the
     *                     original booking already posted it or left the invoice
     *                     PARTIALLY_UPLOADED for the retry batchlet.
     */
    private Invoice applyBookedState(Invoice inv, Integer bookedNumber, String sendBy,
                                     String invoiceUuid, String idempotencyKey,
                                     boolean freshBooking) {
        if (bookedNumber != null) {
            inv.setEconomicsBookedNumber(bookedNumber);
            inv.setInvoicenumber(bookedNumber);
        }
        inv.setStatus(InvoiceStatus.CREATED);
        inv.setEconomicsStatus(EconomicsInvoiceStatus.BOOKED);
        inv.setSendBy(sendBy);  // persist delivery method for E-Invoicing tracking

        // Mark work items paid-out only AFTER the booking durably commits.
        // Running it inline (even via a REQUIRES_NEW facade) shares the Hibernate session,
        // so a `work`-table "Lock wait timeout" auto-flushed and rolled back the just-persisted
        // booked state — reverting the invoice to PENDING_REVIEW while e-conomic kept the
        // booking (split-brain: invoice dba892b4 / bookedNumber 28084, 2026-06-30). An
        // AFTER_SUCCESS observer (InvoiceBookedPayoutObserver) runs the payout in its own
        // transaction after commit, so a payout failure can no longer undo the booking.
        //
        // Fired on the short-circuit path too: registerAsPaidout is idempotent
        // (WHERE uuid = ?2 AND paidOut IS NULL), so re-firing heals an earlier run that booked
        // at the vendor but died before the payout.
        invoiceBooked.fire(new InvoiceBookedEvent(
                inv.getUuid(), inv.getContractuuid(), inv.getProjectuuid(),
                inv.getMonth(), inv.getYear(), bookedNumber, idempotencyKey));

        if (freshBooking) {
            postDebtorSideVoucherIfInternal(inv);
        }

        log.infof("bookDraft: invoiceUuid=%s bookedNumber=%s COMMITTED",
                invoiceUuid, bookedNumber);
        return inv;
    }

    /**
     * True when a JTA transaction is active on this thread — i.e. bookDraft was entered through a
     * {@code @Transactional} caller ({@code InternalInvoiceOrchestrator.finalizeAutomatically}).
     * Conservative on lookup failure: no-transaction is the interactive default, where the combined
     * durable write is both required and safe.
     */
    private boolean hasActiveTransaction() {
        // Null only in unit tests that build this orchestrator without a container; there is no
        // JTA transaction in that context either, so no-transaction is the truthful answer.
        if (transactionManager == null) return false;
        try {
            return transactionManager.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION;
        } catch (jakarta.transaction.SystemException e) {
            log.warn("bookDraft: could not determine transaction status — assuming none", e);
            return false;
        }
    }

    /**
     * Debtor-voucher entry point for {@code InternalInvoiceOrchestrator.adoptVendorBooking}: posts
     * the supplier voucher for a booking that was reconciled after the fact. The caller must have
     * set {@code grandTotal} (transient) to the vendor-verified gross first. Failure demotes to
     * {@code PARTIALLY_UPLOADED} exactly like the inline path.
     */
    void postDebtorVoucherAfterReconcile(Invoice inv) {
        postDebtorSideVoucherIfInternal(inv);
    }

    private void postDebtorSideVoucherIfInternal(Invoice inv) {
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
        //
        // INTERNAL / INTERNAL_SERVICE invoices and internal CREDIT_NOTE reversals (a
        // CREDIT_NOTE carrying a debtor) post a debtor-side voucher. A client CREDIT_NOTE
        // (no debtor) stays skipped → regular client credit notes are untouched.
        if (inv.getType() == InvoiceType.INTERNAL
                || inv.getType() == InvoiceType.INTERNAL_SERVICE
                || inv.isInternalCreditNote()) {
            postDebtorSideVoucher(inv);
        }
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
            perfMetrics.emitCount("InvoiceFinalizePartialUpload", 1,
                    java.util.Map.of(), java.util.Map.of());
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
                .orElseThrow(() -> new NotFoundException(
                        "Invoice not found: " + invoiceUuid));

        // A booked invoice exists in e-conomic and cannot be "un-finalized" by reverting
        // it to DRAFT — doing so previously re-opened the row for deletion and orphaned
        // the e-conomic document (incident 2026-05-01). Reject defensively; the booked
        // number/voucher should normally only ever coexist with status CREATED, but guard
        // on the durable e-conomic markers directly rather than trusting status alone.
        if (inv.getEconomicsBookedNumber() != null || inv.getEconomicsVoucherNumber() > 0) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Invoice " + invoiceUuid + " is already booked in e-conomic "
                            + "(bookedNumber=" + inv.getEconomicsBookedNumber() + ") — cannot cancel "
                            + "finalization. Credit or void it in e-conomic instead.")
                    .build());
        }

        // The guard above reads the invoice row, which is exactly what a split-brain leaves
        // un-updated: on 2026-08-07 e-conomic booked 28214 while the local write rolled back, so
        // economicsBookedNumber was NULL and economicsVoucherNumber 0. The guard passed, and had
        // PendingReviewCleanupBatchlet reached this invoice it would have deleted a booked
        // document's draft and reverted to DRAFT. Consult the outbox as a second, independent
        // witness to what e-conomic was actually told.
        Optional<InvoiceBookingAttempt> bookedAttempt = attemptRepo.findBookedByInvoice(invoiceUuid);
        if (bookedAttempt.isPresent()) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Invoice " + invoiceUuid + " has a booking attempt recorded as BOOKED "
                            + "at e-conomic (bookedNumber=" + bookedAttempt.get().getBookedNumber()
                            + ", attempt " + bookedAttempt.get().getUuid() + ") even though the "
                            + "invoice row does not show it. Reconcile before cancelling — "
                            + "cancelling now would orphan a booked e-conomic document.")
                    .build());
        }
        Optional<InvoiceBookingAttempt> unresolved = attemptRepo.findLatestByInvoice(invoiceUuid)
                .filter(a -> a.getState() == InvoiceBookingAttempt.State.NEEDS_RECONCILIATION
                        || (a.getState() == InvoiceBookingAttempt.State.PENDING && a.getPostedAt() != null));
        if (unresolved.isPresent()) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity("Invoice " + invoiceUuid + " has a booking attempt that reached "
                            + "e-conomic with an unresolved outcome (attempt "
                            + unresolved.get().getUuid() + ", state " + unresolved.get().getState()
                            + ", posted " + unresolved.get().getPostedAt() + "). Confirm in "
                            + "e-conomic whether it booked before cancelling.")
                    .build());
        }

        if (inv.getStatus() != InvoiceStatus.PENDING_REVIEW) {
            throw new BadRequestException(
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

        // The draft this invoice's open attempts were built against no longer exists. Retire them
        // so a later createDraft reserves a fresh row rather than matching a stale frozen
        // draft_invoice_number that now names a deleted document.
        attempts.markSuperseded(invoiceUuid);

        log.infof("cancelFinalization: invoiceUuid=%s reverted to DRAFT", invoiceUuid);
        return inv;
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Tx1 of the outbox. Resolves {@code draftInvoiceNumber} at most once per (invoice, draft) pair
     * and freezes it, together with the idempotency key and the serialised body, in a committed row.
     *
     * <p>The live {@code getByNumber} lookup happens only when a row must be created. On the retry
     * path the frozen value is reused verbatim — re-resolving it is what changed the payload under
     * an unchanged key and produced the 2026-08-07 {@code PayloadChanged} failures.
     *
     * <p>The idempotency key is deliberately the invariant {@code "book-" + invoiceUuid}, with no
     * per-attempt suffix. During a blue/green rollout old tasks still send exactly that string; if
     * new tasks sent a different one, an old task's booking and a new task's booking would no longer
     * collide at the vendor and would silently produce two booked invoices. The per-attempt suffix
     * (mirroring {@code createDraft}) belongs in a follow-up deploy, once no old tasks remain.
     */
    private InvoiceBookingAttempt reserveBookingAttempt(String invoiceUuid,
                                                        String companyUuid,
                                                        EconomicsAgreementResolver.Tokens tokens,
                                                        Invoice inv,
                                                        String sendBy) {
        Optional<InvoiceBookingAttempt> booked = attemptRepo.findBookedByInvoice(invoiceUuid);
        if (booked.isPresent()) return booked.get();

        // Q2C POST /invoices/drafts returns CreatedResult.number (internal identifier),
        // but the legacy REST booking endpoint requires draftInvoiceNumber (the number
        // shown in the UI). The two are NOT the same — e-conomic maintains separate
        // counters per API surface. Resolve number → draftInvoiceNumber here so the
        // legacy book call receives the identifier it actually understands.
        EconomicsDraftInvoice draft = draftApi.getByNumber(
                tokens.appSecret(),
                tokens.agreementGrant(),
                inv.getEconomicsDraftNumber());
        if (draft == null || draft.getDraftInvoiceNumber() == null) {
            throw new BadRequestException(
                    "e-conomic returned no draftInvoiceNumber for draft " + inv.getEconomicsDraftNumber()
                    + " (invoice " + invoiceUuid + "). Cancel finalization and try again.");
        }
        int draftInvoiceNumber = draft.getDraftInvoiceNumber();

        assertNoUnresolvedPostedSibling(
                attemptRepo.listPostedUnresolvedByInvoice(invoiceUuid), draftInvoiceNumber, invoiceUuid);

        return attempts.reserve(
                invoiceUuid,
                companyUuid,
                inv.getEconomicsDraftNumber(),
                draftInvoiceNumber,
                sendBy,
                "book-" + invoiceUuid,
                EconomicsBookingRequest.of(draftInvoiceNumber, sendBy));
    }

    /**
     * Fail-closed double-booking guard. The booking idempotency key is the invariant
     * {@code "book-" + invoiceUuid} (blue/green requirement, see {@link #reserveBookingAttempt}),
     * so a NEW attempt for the same invoice sends a <em>different body under the same key</em>: a
     * {@code PayloadChanged} 400 inside the vendor's one-hour TTL, and a <b>second booked
     * invoice</b> after it — the exact mechanism of the 2026-08-07 duplicate (28218). While any
     * earlier attempt has been POSTed but not resolved (PENDING with {@code posted_at}, or
     * NEEDS_RECONCILIATION), refuse to mint a new one before anything goes out the door.
     * Replaying the SAME attempt (same draftInvoiceNumber) stays allowed — that is the designed
     * recovery. Resolution paths: {@code InternalInvoiceOrchestrator.adoptVendorBooking} when the
     * booked number is known (logs / vendor UI), or manual reconciliation at e-conomic.
     */
    static void assertNoUnresolvedPostedSibling(List<InvoiceBookingAttempt> postedUnresolved,
                                                int draftInvoiceNumber,
                                                String invoiceUuid) {
        for (InvoiceBookingAttempt a : postedUnresolved) {
            if (a.getDraftInvoiceNumber() != draftInvoiceNumber) {
                throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                        .entity("Invoice " + invoiceUuid + " has an earlier booking attempt ("
                                + a.getUuid() + ", draftInvoiceNumber " + a.getDraftInvoiceNumber()
                                + ", posted at " + a.getPostedAt() + ", state " + a.getState()
                                + ") whose e-conomic outcome is unresolved. Booking again could "
                                + "create a duplicate booked invoice. Reconcile the earlier attempt "
                                + "first (adopt its booked number via the reconcile endpoint, or "
                                + "resolve it at e-conomic).")
                        .build());
            }
        }
    }

    /**
     * Classifies an HTTP error from the booking call and records it against the attempt.
     *
     * <p>A {@code PayloadChanged} 400 must now be unreachable — the body is frozen. If it ever
     * fires again, our persisted payload has diverged from what we sent, which is not something a
     * retry can fix, so the attempt becomes a reconciliation case rather than a retryable failure.
     *
     * @return the exception to rethrow, so callers can {@code throw classifyBookingFailure(...)}
     */
    private RuntimeException classifyBookingFailure(InvoiceBookingAttempt attempt,
                                                    String invoiceUuid,
                                                    WebApplicationException e) {
        int status = e.getResponse() != null ? e.getResponse().getStatus() : 0;
        String message = String.valueOf(e.getMessage());
        boolean idempotencyConflict = status == 400
                && (message.contains("Idempotency") || message.contains("PayloadChanged"));

        if (idempotencyConflict) {
            attempts.markNeedsReconciliation(attempt.getUuid(),
                    "e-conomic reported PayloadChanged against a frozen body — " + message);
        } else if (status >= 400 && status < 500 && status != 409) {
            attempts.markFailed(attempt.getUuid(), "HTTP " + status + " — " + message);
        } else {
            // 409/5xx: retryable, but only inside the idempotency window. Leave PENDING so the
            // next attempt replays the same key and body; isReplaySafe() guards the deadline.
            InvoiceBookingAttempt fresh = attemptRepo.findById(attempt.getUuid());
            attempts.markPostedFailure(attempt.getUuid(), "HTTP " + status + " — " + message);
            log.warnf("bookDraft: retryable e-conomic failure for invoiceUuid=%s attempt=%s "
                            + "status=%d — attempt stays PENDING, replay window ends %s",
                    invoiceUuid, attempt.getUuid(), status,
                    fresh != null && fresh.getPostedAt() != null
                            ? fresh.getPostedAt().plusMinutes(InvoiceBookingAttempt.IDEMPOTENCY_TTL_MINUTES)
                            : "unknown");
        }
        return e;
    }

    /**
     * Loads an invoice for finalization, serialising concurrent attempts on the row.
     *
     * <p>The row lock is taken <em>before</em> the status is read, so the check-then-act window is
     * closed: a second finalize blocks here until the first commits, then reads the status the
     * first left behind (PENDING_REVIEW) and is correctly rejected — instead of both proceeding to
     * create real e-conomic drafts.
     */
    private Invoice requireEditableInvoice(String uuid) {
        Invoice inv = invoices.findByUuidForUpdate(uuid)
                .orElseThrow(() -> new NotFoundException(
                        "Invoice not found: " + uuid));
        if (inv.getStatus() != InvoiceStatus.DRAFT
                && inv.getStatus() != InvoiceStatus.QUEUED) {
            throw new BadRequestException(
                    "Invoice " + uuid + " is not DRAFT/QUEUED (status=" + inv.getStatus() + ")");
        }
        return inv;
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

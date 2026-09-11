package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight;
import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight.PeriodState;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.pricing.PriceResult;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceLineGenerator;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
import dk.trustworks.intranet.aggregates.invoice.services.SourceItemMerger;
import dk.trustworks.intranet.aggregates.invoice.services.UserCompanyResolver;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledDeltaQuery;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledPaidGate;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-invoice finalizer for the queued-internal-invoice batch. Each public {@code processOne*} /
 * discovery method runs in its OWN {@link Transactional.TxType#REQUIRES_NEW} transaction so that a
 * single invoice's e-conomics {@code book()} failure rolls back only that invoice — not the whole
 * batch run. Without this isolation a single failed booking marked the shared batch transaction
 * rollback-only, reverting every booking in the run to QUEUED while e-conomics kept them, causing
 * nightly re-booking duplicates.
 *
 * <p>The orchestration loop lives in {@link QueuedInternalInvoiceProcessorBatchlet}, which is
 * intentionally NON-transactional.
 *
 * SPEC-INV-001 §9.1, §9.2.
 */
@ApplicationScoped
@JBossLog
public class QueuedInternalInvoiceFinalizer {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Result of attempting to finalize a single queued invoice. */
    public enum Outcome {
        /** Both sides posted: issuer booked, debtor voucher accepted, economics_status BOOKED. */
        PROCESSED,
        /** Not eligible yet (or no longer), nothing was sent to e-conomic. */
        SKIPPED,
        /**
         * The ISSUER side booked at e-conomic and is committed locally, but the DEBTOR-side
         * supplier voucher was refused and the row is left at economics_status
         * PARTIALLY_UPLOADED. This is not a success: the two intercompany books disagree, and no
         * job retries the debtor voucher (the upload-retry batchlet only reads
         * {@code invoice_economics_uploads}, which this Q2C path never writes). Until 2026-09-10
         * this case was logged as "Successfully auto-finalized" — invoice 7803f536 / 70479,
         * 2026-09-09 — and was invisible to ops.
         */
        HALF_BOOKED
    }

    /**
     * Log token for a half-booked internal invoice, styled after
     * {@code ECONOMICS_UPLOAD_TERMINAL_FAILED} so a CloudWatch metric filter can alarm on it. Emitted
     * at ERROR exactly once per occurrence: the invoice leaves QUEUED, so the nightly run never
     * sees it again.
     */
    public static final String HALF_BOOKED_TOKEN = "INTERNAL_INVOICE_HALF_BOOKED";

    @Inject
    InternalInvoiceOrchestrator internalOrchestrator;

    @Inject
    InvoiceAttributionService invoiceAttributionService;

    @Inject
    UserCompanyResolver userCompanyResolver;

    @Inject
    PricingEngine pricingEngine;

    @Inject
    SelfBilledDeltaQuery selfBilledDeltaQuery;

    /** Decides which period is open in BOTH companies, so a back-dated finalization is safe. */
    @Inject
    AccountingPeriodPreflight periodPreflight;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "feature.invoicing.internal.attribution-driven", defaultValue = "true")
    boolean attributionDrivenInternalInvoices;

    /**
     * First-pass discovery: uuids of all QUEUED INTERNAL invoices that reference another invoice.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<String> findFirstPassUuids() {
        List<Invoice> queuedInvoices = Invoice.list(
                "status = ?1 AND type = ?2 AND invoiceref > 0",
                InvoiceStatus.QUEUED, InvoiceType.INTERNAL
        );
        return queuedInvoices.stream().map(Invoice::getUuid).collect(Collectors.toList());
    }

    /**
     * Second-pass discovery (Feature 3c): uuids of QUEUED settlement INTERNALs that reference a
     * PHANTOM source.
     *
     * <p>Native, parameter-free discovery (selfbilled idiom): QUEUED settlement INTERNALs whose
     * invoice_ref_uuid points at a PHANTOM source. A separate native query keeps the PHANTOM-type
     * join out of HQL (no Panache active-record subquery precedent in this codebase).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<String> findSettlementUuids() {
        return em.createNativeQuery("""
                SELECT i.uuid
                FROM invoices i
                JOIN invoices src ON src.uuid = i.invoice_ref_uuid
                WHERE i.type = 'INTERNAL' AND i.status = 'QUEUED'
                  AND i.settlement_billing_client_uuid IS NOT NULL
                  AND i.settlement_year IS NOT NULL AND i.settlement_month IS NOT NULL
                  AND src.type = 'PHANTOM'
                """).getResultList();
    }

    /**
     * Finalize a single first-pass queued internal invoice in its own REQUIRES_NEW transaction.
     *
     * <p>Re-fetches the queued invoice by uuid (it may have changed since discovery), checks the
     * referenced invoice is PAID, optionally regenerates items from current attribution, then
     * delegates to {@link InternalInvoiceOrchestrator#finalizeAutomatically(String)} which creates
     * the e-conomics draft and books it. Any exception propagates and rolls back ONLY this invoice's
     * transaction (the orchestrator loop catches it and records a failure). A return is NOT
     * automatically a success — see {@link #outcomeOf}.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Outcome processOne(String queuedInvoiceUuid) {
        Invoice queuedInvoice = Invoice.<Invoice>find("uuid", queuedInvoiceUuid).firstResult();
        if (queuedInvoice == null) {
            log.warnf("Queued invoice %s no longer exists - skipping", queuedInvoiceUuid);
            return Outcome.SKIPPED;
        }

        // Find the referenced external invoice
        Invoice referencedInvoice = Invoice.find(
                "uuid = ?1",
                queuedInvoice.getInvoiceRefUuid()
        ).firstResult();

        if (referencedInvoice == null) {
            log.warnf("Queued invoice %s references non-existent invoice %s - skipping",
                    queuedInvoice.getUuid(), queuedInvoice.getInvoiceRefUuid());
            return Outcome.SKIPPED;
        }

        // Only proceed when the referenced invoice is confirmed PAID
        if (referencedInvoice.getEconomicsStatus() != EconomicsInvoiceStatus.PAID) {
            log.debugf("Queued invoice %s waiting for invoice %s to be PAID (current: %s)",
                    queuedInvoice.getUuid(),
                    referencedInvoice.getUuid(),
                    referencedInvoice.getEconomicsStatus());
            return Outcome.SKIPPED;
        }

        // Regenerate items from current source attribution BEFORE finalization
        // (spec §5.4). If the regeneration yields zero lines for this issuer
        // (attribution shifted so this issuer no longer has cross-company work),
        // delete the QUEUED invoice, log a WARN, and continue — no e-conomics
        // cleanup needed because QUEUED never created an e-conomics draft.
        if (attributionDrivenInternalInvoices
                && !regenerateQueuedItems(queuedInvoice, referencedInvoice)) {
            return Outcome.SKIPPED;
        }

        // Date it into the period of the client invoice it mirrors, when that period is still
        // open. See chooseFinalizationDate — "today" is only right when the client paid promptly.
        applyFinalizationDate(queuedInvoice, referencedInvoice.getInvoicedate(),
                "client invoice " + referencedInvoice.getInvoicenumber());

        log.infof("Auto-finalizing queued invoice %s (references paid invoice %s)",
                queuedInvoice.getUuid(), referencedInvoice.getUuid());

        // Auto-finalize: create draft + book immediately, no review step (SPEC-INV-001 §9.1)
        Invoice finalized = internalOrchestrator.finalizeAutomatically(queuedInvoice.getUuid());

        return outcomeOf(finalized, "queued invoice");
    }

    /**
     * Finalize a single QUEUED settlement INTERNAL (PHANTOM-referenced) in its own REQUIRES_NEW
     * transaction.
     *
     * <p>These never match the first pass — a settlement internal references a PHANTOM (not a real
     * source invoice with {@code economics_status = PAID}), and it carries DELTA lines that must NOT
     * be regenerated from source attribution (that would re-derive the full amount and over-book).
     * Instead they go through the self-billed paid-gate: a settlement internal may finalize only when
     * the client has paid every self-billing voucher backing its (client, consultant, work-period)
     * group — i.e. every backing voucher's 8610 'Samlekonto debitorer' remainder is exactly 0
     * (reusing {@link SelfBilledDeltaQuery#voucherRemainders} + {@link SelfBilledPaidGate#allPaid},
     * the SAME lookup as the workbench {@code /internals/queued} read — no duplicated SQL).
     * Fail-closed: a voucher with no 8610 row (null remainder) or an empty backing set means NOT paid
     * -> skip. No item regeneration.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Outcome processOneSettlement(String settlementInvoiceUuid) {
        Invoice internal = Invoice.<Invoice>find("uuid", settlementInvoiceUuid).firstResult();
        if (internal == null) {
            log.warnf("Settlement internal %s no longer exists - skipping", settlementInvoiceUuid);
            return Outcome.SKIPPED;
        }

        String consultant = settlementConsultant(internal);
        if (consultant == null) {
            log.warnf("Settlement internal %s has no single item consultant — skipping", internal.getUuid());
            return Outcome.SKIPPED;
        }
        List<SelfBilledPaidGate.VoucherRemainder> remainders = selfBilledDeltaQuery.voucherRemainders(
                internal.getSettlementBillingClientUuid(), internal.getSettlementDebtorCompanyuuid(),
                consultant, internal.getSettlementYear(), internal.getSettlementMonth());

        if (!SelfBilledPaidGate.allPaid(remainders)) {
            log.debugf("Settlement internal %s not yet paid (client=%s consultant=%s %d-%02d, "
                            + "backingVouchers=%d) — skipping",
                    internal.getUuid(), internal.getSettlementBillingClientUuid(), consultant,
                    internal.getSettlementYear(), internal.getSettlementMonth(), remainders.size());
            return Outcome.SKIPPED;
        }

        // A settlement internal has no source invoice; its period is the month it settles.
        applyFinalizationDate(internal, endOfSettlementMonth(internal),
                "settlement month " + internal.getSettlementYear() + "-"
                        + String.format("%02d", internal.getSettlementMonth()));
        log.infof("Auto-finalizing settlement internal %s (self-billing vouchers paid)", internal.getUuid());
        Invoice finalized = internalOrchestrator.finalizeAutomatically(internal.getUuid());
        return outcomeOf(finalized, "settlement internal");
    }

    /**
     * Reads the outcome off the finalized invoice instead of assuming that a return without an
     * exception means both sides posted.
     *
     * <p>{@code finalizeAutomatically} deliberately swallows a DEBTOR-side voucher failure: the
     * issuer booking is irreversible at e-conomic, so throwing would roll back the local record of
     * a booking that really happened (the 2026-08-07 split-brain). The price is that the return
     * value is the only signal, and until 2026-09-10 nobody read it — the job logged "Successfully
     * auto-finalized" over a PARTIALLY_UPLOADED row. This method must likewise never throw: the
     * enclosing REQUIRES_NEW transaction has to commit the half-booked state so that the booked
     * number survives.
     *
     * @param finalized the invoice as returned by the orchestrator
     * @param what      "queued invoice" or "settlement internal", for the log line
     */
    // Package-private so the outcome decision is testable without Panache statics.
    Outcome outcomeOf(Invoice finalized, String what) {
        if (finalized != null
                && finalized.getEconomicsStatus() == EconomicsInvoiceStatus.PARTIALLY_UPLOADED) {
            String issuer = finalized.getCompany() != null ? finalized.getCompany().getUuid() : null;
            log.errorf("%s: %s %s is HALF-BOOKED — the ISSUER side (company %s) booked at e-conomic "
                            + "as %s, but the DEBTOR-side supplier voucher to company %s was refused "
                            + "and economics_status is PARTIALLY_UPLOADED. Nothing retries this "
                            + "automatically. Check the debtor's e-conomic journal for an existing "
                            + "voucher, fix the cause (see the preceding DEBTOR-side voucher WARN), "
                            + "then post the debtor voucher via "
                            + "POST /invoices/internalservices/{uuid}/reconcile-booking?bookedNumber=%s "
                            + "and set economics_status=BOOKED.",
                    HALF_BOOKED_TOKEN, what, finalized.getUuid(), issuer,
                    finalized.getEconomicsBookedNumber(), finalized.getDebtorCompanyuuid(),
                    finalized.getEconomicsBookedNumber());
            return Outcome.HALF_BOOKED;
        }
        log.infof("Successfully auto-finalized %s %s (bookedNumber=%s, economicsStatus=%s)", what,
                finalized != null ? finalized.getUuid() : null,
                finalized != null ? finalized.getEconomicsBookedNumber() : null,
                finalized != null ? finalized.getEconomicsStatus() : null);
        return Outcome.PROCESSED;
    }

    /**
     * Stamps {@code invoicedate}/{@code duedate} on an invoice about to be finalized, preferring
     * the period the work actually belongs to over the day the job happens to run.
     *
     * <h2>Why not just "today"</h2>
     * An internal invoice is the intercompany mirror of something that already happened. This job
     * fires when the client pays, which is typically one to three months after the work was billed
     * out — 239 of 273 booked internals are dated later than the invoice they mirror, 98 of them by
     * more than two months. Inside a financial year that only shifts the monthly phasing. Across
     * the 30 June boundary it misstates the subsidiaries' annual revenue, and it has done so at
     * each of the last three year-ends: nine invoices, ~1.86M DKK, every one of them dated July or
     * August, which is the signature of June work paid after the year closed.
     *
     * <h2>Why not just the source period either</h2>
     * By the time payment lands, that period may be closed or barred — and then a job that
     * insisted on it would fail every night instead of booking late, which is strictly worse than
     * the problem it set out to fix.
     *
     * <h2>Why both companies, and why the months in between</h2>
     * An internal invoice is posted twice, on the same date: a sales invoice at the issuer and a
     * supplier voucher at the debtor. Barring is per agreement. Until 2026-09-11 this method asked
     * only the issuer, and five invoices dated 2026-07-31 (70479, 70483, 70485, 70486, 70487) were
     * booked at Trustworks Technology ApS while Trustworks A/S had July barred: issuer side booked,
     * debtor voucher refused, half-booked. The pre-flight in {@code createDraft} now refuses such a
     * date outright — but a refusal every night is a stuck invoice until a human acts, and clients
     * pay late while the parent bars months early, so it would recur monthly.
     *
     * <p>So the choice is: the source date if BOTH companies confirm it open; otherwise the first
     * day of the earliest later month — up to but not including the current one — that both
     * confirm open; otherwise today, the old fallback, which the guard then validates. Each
     * agreement is read once for all candidates. A date no company confirmed (UNKNOWN: vendor
     * error, kill switch, no covering period) is never chosen except as today's fallback, so a
     * vendor hiccup cannot move an accounting period.
     *
     * <p>Every branch is logged at INFO with the date and the reason, so a late booking is visible
     * in the log rather than silently inferred from a date that looks like any other.
     *
     * @param inv        the invoice about to be finalized
     * @param sourceDate the date of the period it belongs to, or null when there is none
     * @param sourceWhat short description of where sourceDate came from, for the log line
     */
    // Package-private so the date decision is testable without going through Panache statics.
    void applyFinalizationDate(Invoice inv, LocalDate sourceDate, String sourceWhat) {
        LocalDate today = LocalDate.now();
        String issuer = inv.getCompany() != null ? inv.getCompany().getUuid() : null;
        // Same gate as the debtor-side voucher post: only an internal that will actually post a
        // debtor voucher has a second agreement to satisfy.
        String debtor = AccountingPeriodPreflight.postsDebtorVoucher(inv) ? inv.getDebtorCompanyuuid() : null;

        if (sourceDate != null && !sourceDate.isAfter(today) && issuer != null) {
            List<LocalDate> candidates = candidateDates(sourceDate, today);
            Map<LocalDate, PeriodState> issuerStates = periodPreflight.classifyDates(issuer, candidates);
            Map<LocalDate, PeriodState> debtorStates = debtor != null
                    ? periodPreflight.classifyDates(debtor, candidates) : Map.of();

            for (LocalDate candidate : candidates) {
                boolean issuerOpen = issuerStates.get(candidate) == PeriodState.OPEN;
                boolean debtorOpen = debtor == null || debtorStates.get(candidate) == PeriodState.OPEN;
                if (!issuerOpen || !debtorOpen) continue;

                stamp(inv, candidate);
                if (candidate.equals(sourceDate)) {
                    log.infof("Finalization date for %s: %s, from %s — that period is open in %s",
                            inv.getUuid(), candidate, sourceWhat,
                            debtor != null ? "both the issuer's and the debtor's e-conomic"
                                    : "the issuer's e-conomic");
                } else {
                    log.infof("Finalization date for %s: %s — the first later month open in %s. Source "
                                    + "period %s from %s was %s",
                            inv.getUuid(), candidate,
                            debtor != null ? "both the issuer's and the debtor's e-conomic"
                                    : "the issuer's e-conomic",
                            sourceDate, sourceWhat, describe(sourceDate, issuerStates, debtorStates, debtor));
                }
                return;
            }
        }

        stamp(inv, today);
        log.infof("Finalization date for %s: %s (today). Source period %s from %s was closed, "
                        + "barred, or could not be confirmed open in every company, and so was every "
                        + "month between",
                inv.getUuid(), today, sourceDate, sourceWhat);
    }

    private static void stamp(Invoice inv, LocalDate date) {
        inv.setInvoicedate(date);
        inv.setDuedate(date.plusDays(1));
    }

    /** "barred/closed at the issuer" / "unconfirmed at the debtor" etc., for the log line. */
    private static String describe(LocalDate date, Map<LocalDate, PeriodState> issuerStates,
                                   Map<LocalDate, PeriodState> debtorStates, String debtor) {
        List<String> parts = new ArrayList<>(2);
        PeriodState i = issuerStates.get(date);
        if (i != PeriodState.OPEN) parts.add((i == PeriodState.BLOCKED ? "closed/barred" : "unconfirmed") + " at the issuer");
        if (debtor != null) {
            PeriodState d = debtorStates.get(date);
            if (d != PeriodState.OPEN) parts.add((d == PeriodState.BLOCKED ? "closed/barred" : "unconfirmed") + " at the debtor");
        }
        return parts.isEmpty() ? "open" : String.join(" and ", parts);
    }

    /**
     * The dates tried, in order: the source date itself, then the first day of each later month
     * strictly before the current month. Today is not in the list — it is the fallback the caller
     * applies when nothing here is confirmed open, and it stays exactly the pre-2026-09 behaviour.
     */
    static List<LocalDate> candidateDates(LocalDate sourceDate, LocalDate today) {
        List<LocalDate> out = new ArrayList<>();
        out.add(sourceDate);
        LocalDate month = sourceDate.withDayOfMonth(1).plusMonths(1);
        LocalDate currentMonth = today.withDayOfMonth(1);
        while (month.isBefore(currentMonth)) {
            out.add(month);
            month = month.plusMonths(1);
        }
        return out;
    }

    /** Last day of the month a settlement internal settles, or null when it carries no period. */
    static LocalDate endOfSettlementMonth(Invoice internal) {
        Integer year = internal.getSettlementYear();
        Integer month = internal.getSettlementMonth();
        if (year == null || month == null || month < 1 || month > 12) return null;
        return LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
    }

    /** The single consultant on a settlement internal's items, or null if items carry zero or >1 consultants. */
    private String settlementConsultant(Invoice internal) {
        List<InvoiceItem> items = internal.getInvoiceitems();
        if (items == null || items.isEmpty()) return null;
        String consultant = null;
        for (InvoiceItem item : items) {
            if (item.consultantuuid == null || item.consultantuuid.isBlank()) continue;
            if (consultant == null) {
                consultant = item.consultantuuid;
            } else if (!consultant.equals(item.consultantuuid)) {
                return null;   // ambiguous — never guess
            }
        }
        return consultant;
    }

    /**
     * Regenerate the items on a QUEUED internal invoice from the source invoice's
     * current attribution state, filtered to this invoice's issuer company.
     *
     * <p>Returns {@code true} if regeneration produced one or more lines; the caller
     * proceeds with finalization. Returns {@code false} if no cross-company lines
     * remain — in that case the QUEUED invoice is deleted and a WARN is logged
     * (per user decision #2 from spec review: log only, no email/Slack).
     *
     * <p>The WARN message contains both the issuer {@code companyUuid} and the
     * source invoice's period formatted as {@code YYYY-MM} so Ops can quickly
     * correlate the event with a period in the controlling view.
     */
    private boolean regenerateQueuedItems(Invoice queuedInvoice, Invoice sourceInvoice) {
        List<InvoiceItemAttribution> attributions =
                invoiceAttributionService.getInvoiceAttributions(sourceInvoice.getUuid());
        Set<String> consultantUuids = attributions.stream()
                .map(a -> a.consultantUuid)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        LocalDate asOf = sourceInvoice.getInvoicedate() != null
                ? sourceInvoice.getInvoicedate()
                : LocalDate.now();
        Map<String, String> userCompanies = userCompanyResolver.resolveCompanies(consultantUuids, asOf);
        String sourceCompanyUuid = sourceInvoice.getCompany() != null
                ? sourceInvoice.getCompany().getUuid() : null;
        String issuerUuid = queuedInvoice.getCompany() != null
                ? queuedInvoice.getCompany().getUuid() : null;

        // Merge persisted source items with synthetic CALCULATED items from the pricing
        // engine (spec §6.4) before regeneration. Required for sources whose CALCULATED
        // discount/fee lines were never persisted — without the merge the nightly
        // batchlet would over-bill the issuer.
        long mergeStartNanos = System.nanoTime();
        List<InvoiceItem> persisted = sourceInvoice.getInvoiceitems() != null
                ? sourceInvoice.getInvoiceitems() : List.of();
        List<InvoiceItem> synthetics;
        try {
            Map<String, String> cti = loadContractTypeItems(sourceInvoice.getContractuuid());
            PriceResult pr = pricingEngine.price(sourceInvoice, cti);
            synthetics = pr.syntheticItems != null ? pr.syntheticItems : List.of();
        } catch (Exception e) {
            log.warnf(e, "Pricing engine failed for source invoice %s — falling back to "
                    + "persisted items only", sourceInvoice.getUuid());
            synthetics = List.of();
        }
        List<InvoiceItem> mergedItems = SourceItemMerger.merge(persisted, synthetics);
        long durationMs = (System.nanoTime() - mergeStartNanos) / 1_000_000L;

        // Per spec O7.8 — structured per-source instrumentation so we can detect a
        // batchlet runtime regression and decide whether the pricing-cache phase
        // becomes necessary.
        log.infof("InternalInvoiceMerge: sourceUuid=%s persistedCount=%d syntheticCount=%d "
                        + "mergedCount=%d durationMs=%d",
                sourceInvoice.getUuid(), persisted.size(), synthetics.size(),
                mergedItems.size(), durationMs);

        // Synthesize in-memory attributions for synthetic CALCULATED items so the
        // generator emits internal lines for them (spec §6.4 option (a)). Without
        // this step every synthetic CALCULATED line would be silently dropped by
        // {@link InternalInvoiceLineGenerator}.
        Set<String> persistedItemUuids = new HashSet<>(persisted.size() * 2);
        Set<String> baseItemUuids = new HashSet<>();
        for (InvoiceItem p : persisted) {
            if (p == null || p.uuid == null) continue;
            persistedItemUuids.add(p.uuid);
            if (p.origin == dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.BASE) {
                baseItemUuids.add(p.uuid);
            }
        }
        List<InvoiceItem> syntheticCalculated = new ArrayList<>();
        for (InvoiceItem m : mergedItems) {
            if (m == null || m.uuid == null) continue;
            if (m.origin != dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.CALCULATED) continue;
            if (persistedItemUuids.contains(m.uuid)) continue;
            syntheticCalculated.add(m);
        }
        List<InvoiceItemAttribution> effectiveAttributions = attributions;
        if (!syntheticCalculated.isEmpty()) {
            List<InvoiceItemAttribution> syntheticAttrs = SourceItemMerger.synthesizeAttributionsFor(
                    syntheticCalculated, attributions, baseItemUuids);
            if (!syntheticAttrs.isEmpty()) {
                effectiveAttributions = new ArrayList<>(attributions.size() + syntheticAttrs.size());
                effectiveAttributions.addAll(attributions);
                effectiveAttributions.addAll(syntheticAttrs);
            }
        }

        Map<String, List<InvoiceItem>> grouped = InternalInvoiceLineGenerator.generate(
                sourceCompanyUuid, mergedItems, effectiveAttributions, userCompanies);

        List<InvoiceItem> newLines = grouped.getOrDefault(issuerUuid, List.of());
        long previousCount = queuedInvoice.getInvoiceitems() != null
                ? queuedInvoice.getInvoiceitems().size() : 0;

        if (newLines.isEmpty()) {
            String period = asOf.format(PERIOD_FORMAT);
            log.warnf("Queued internal invoice %s for companyUuid=%s period=%s has no "
                            + "cross-company attribution remaining — deleting QUEUED row (no e-conomics "
                            + "draft existed).",
                    queuedInvoice.getUuid(), issuerUuid, period);
            // Delete the QUEUED invoice and its items — no e-conomics cleanup needed. Delete via the
            // MANAGED entity so cascade REMOVE takes the eager-loaded items in the PC; a JPQL bulk item
            // delete bypasses the PC, leaving the cascade to issue per-row deletes that affect 0 rows ->
            // OptimisticLockException rolling back the whole tx (DB FK fk_invoiceitems_invoice ON DELETE
            // CASCADE, V173, is the backstop).
            queuedInvoice.delete();
            return false;
        }

        // Replace items on the QUEUED invoice and log the delta.
        InvoiceItem.delete("invoiceuuid", queuedInvoice.getUuid());
        if (queuedInvoice.getInvoiceitems() != null) {
            queuedInvoice.getInvoiceitems().clear();
        }
        int position = 1;
        for (InvoiceItem line : newLines) {
            line.invoiceuuid = queuedInvoice.getUuid();
            line.position = position++;
            InvoiceItem.persist(line);
            if (queuedInvoice.getInvoiceitems() != null) {
                queuedInvoice.getInvoiceitems().add(line);
            }
        }
        log.infof("Regenerated QUEUED invoice %s items: %d -> %d",
                queuedInvoice.getUuid(), previousCount, newLines.size());
        return true;
    }

    /**
     * Load {@code contract_type_items} key/value pairs for the source contract — the
     * pricing engine consumes these for dynamic discount-parameter resolution.
     */
    private Map<String, String> loadContractTypeItems(String contractuuid) {
        Map<String, String> cti = new HashMap<>();
        if (contractuuid == null || contractuuid.isBlank()) return cti;
        ContractTypeItem.<ContractTypeItem>find("contractuuid", contractuuid)
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));
        return cti;
    }
}

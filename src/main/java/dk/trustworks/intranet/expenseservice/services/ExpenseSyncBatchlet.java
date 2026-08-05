package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsRateLimitException;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@JBossLog
@Dependent
@Named("expenseSyncBatchlet")
@BatchExceptionTracking
public class ExpenseSyncBatchlet extends AbstractBatchlet {

    @Inject
    EconomicsService economicsService;

    @Inject
    ExpenseService expenseService;

    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.recency-days", defaultValue = "30")
    int syncRecencyDays;

    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.max-retries", defaultValue = "3")
    int syncMaxRetries;

    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.abort-threshold", defaultValue = "15")
    int syncAbortThreshold;

    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.pacing-ms", defaultValue = "0")
    long syncPacingMs;

    /**
     * Deletion grace period: consecutive "voucher not found anywhere" runs required
     * before an expense is marked DELETED. The accountant moves unbooked vouchers
     * into temporary journals around fiscal-year start and back after booking, so a
     * transiently missing voucher reappears and the counter resets — it is never
     * wrongly deleted (2026-07-28 incident: 224 false DELETEs on the first miss).
     */
    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.delete-miss-threshold", defaultValue = "3")
    int syncDeleteMissThreshold;

    /** Deletion circuit breaker: max expenses one run may mark DELETED (absolute). */
    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.delete-abort-threshold", defaultValue = "20")
    int syncDeleteAbortThreshold;

    /** Deletion circuit breaker: max DELETEs as a percentage of the selected set (0 disables). */
    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.delete-abort-percent", defaultValue = "5.0")
    double syncDeleteAbortPercent;

    /** One page comfortably covers every journal a tenant realistically has. */
    static final int JOURNALS_PAGESIZE = 100;

    /** Per-item result fed to the run-level circuit breaker. */
    enum SyncOutcome { SUCCESS, THROTTLED, ERROR }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

    @Override
    @ActivateRequestContext
    public String process() throws Exception {
        try {
            // Volume fix (Design A): re-sync only what can still change.
            LocalDate cutoff = computeCutoff(LocalDate.now(), syncRecencyDays);
            List<Expense> expenses = selectExpensesToSync(cutoff);
            log.info("ExpenseSyncBatchlet processing expenses: " + expenses.size());

            // Cascade fix (Design B): retry transient 429s per item, and abort the
            // whole run if e-conomic keeps throttling — the unsynced remainder is
            // small now and re-attempts next night.
            EconomicsRetryExecutor retry =
                    new EconomicsRetryExecutor(syncMaxRetries, EconomicsRetryExecutor.Sleeper.REAL);
            ThrottleCircuitBreaker breaker = new ThrottleCircuitBreaker(syncAbortThreshold);

            // Deletion is DEFERRED: syncExpense only queues candidates, and the whole
            // batch of DELETEs is applied (or refused) at the end of the run by
            // applyDeletionPhase — so a bad night is "0 deleted + 1 alert", never
            // "N deleted before the breaker noticed".
            List<Expense> deletionCandidates = new java.util.ArrayList<>();

            for (Expense expense : expenses) {
                SyncOutcome outcome = syncExpense(expense, retry, deletionCandidates);
                if (outcome == SyncOutcome.THROTTLED) {
                    breaker.recordThrottled();
                    if (breaker.isTripped()) {
                        log.warn("e-conomic sustained throttling; aborting expense-sync, will resume next run "
                                + "(consecutive throttled=" + breaker.getConsecutiveThrottled()
                                + ", threshold=" + syncAbortThreshold + ")");
                        break;
                    }
                } else {
                    breaker.reset();
                }
                if (syncPacingMs > 0) {
                    try {
                        Thread.sleep(syncPacingMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("expense-sync interrupted during pacing; stopping run");
                        break;
                    }
                }
            }

            applyDeletionPhase(deletionCandidates, expenses.size());
            return "COMPLETED";
        } catch (Exception e) {
            log.error("ExpenseSyncBatchlet failed", e);
            throw e;
        }
    }

    /** Recency cutoff = today − recencyDays. Extracted (pure) for unit testing. */
    static LocalDate computeCutoff(LocalDate today, int recencyDays) {
        return today.minusDays(recencyDays);
    }

    /**
     * The narrowed selection: rows with a complete voucher triple that are either
     * still VERIFIED_UNBOOKED (can flip to booked/deleted) or were modified on/after
     * {@code cutoff}. {@code datemodified} is only bumped on genuine business
     * activity (create/edit/review) — the bulk-update in
     * {@link ExpenseService#updateStatus} does not touch it — so the sync can never
     * self-perpetuate the window. Extracted as a static seam so the selection is
     * testable with seeded rows (ExpenseSyncSelectionIT).
     */
    static List<Expense> selectExpensesToSync(LocalDate cutoff) {
        return Expense.find(
                "vouchernumber > 0 and journalnumber is not null and accountingyear is not null " +
                "and (status = ?1 or datemodified >= ?2)",
                ExpenseService.STATUS_VERIFIED_UNBOOKED, cutoff).list();
    }

    SyncOutcome syncExpense(Expense expense, EconomicsRetryExecutor retry, List<Expense> deletionCandidates) {
        try (EconomicsAPI api = economicsService.getApiForExpense(expense)) {
            int jn = expense.getJournalnumber();
            String year = expense.getAccountingyear();
            int vn = expense.getVouchernumber();

            log.info("Expense sync start: uuid=" + expense.getUuid() + ", status=" + expense.getStatus() + ", account=" + expense.getAccount() + ", triple=" + jn + "/" + year + "-" + vn);

            // 1) Look in journal entries (unbooked)
            String journalFilter = "voucher.voucherNumber$eq:" + vn;
            Response jr = retry.executeWithRetry(() -> failOnThrottle(api.getJournalEntries(jn, journalFilter, 1000)));
            int jrStatus = jr != null ? jr.getStatus() : -1;
            String jrBody = null;
            try {
                if (jr != null) jrBody = jr.readEntity(String.class);
            } finally {
                if (jr != null) jr.close();
            }
            log.info("Expense " + expense.getUuid() + ": journal query jn=" + jn + ", filter='" + journalFilter + "', status=" + jrStatus);
            log.debug("Journal response body (truncated): " + truncate(jrBody, 800));
            if (jrStatus == 404) {
                // The stored journal itself may be gone (the accountant deletes emptied
                // temporary journals). Not fatal: the booked lookup and the all-journals
                // sweep below still decide the outcome.
                log.warn("Expense " + expense.getUuid() + ": stored journal " + jn + " not found (404); continuing with booked lookup and journal sweep");
            } else if (!isSuccessStatus(jrStatus)) {
                log.warn("Expense " + expense.getUuid() + ": journal query failed with status=" + jrStatus + "; leaving status unchanged");
                return SyncOutcome.ERROR;
            }

            boolean inJournal = isSuccessStatus(jrStatus) && hasAnyEntries(jrBody);
            if (inJournal) {
                applyAccountAndNotes(expense, jrBody, "journal");
                expenseService.updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
                resetSyncMissCountIfNeeded(expense);
                log.info("Expense " + expense.getUuid() + " marked VERIFIED_UNBOOKED (unbooked in journal)");
                return SyncOutcome.SUCCESS;
            }

            // 2) If not in journal, check booked entries under accounting-years
            String yearFilter = "voucherNumber$eq:" + vn;
            // Convert to underscore format for accounting-years path parameter
            String yearId = dk.trustworks.intranet.utils.DateUtils.toEconomicsUrlYear(year);
            Response yr = retry.executeWithRetry(() -> failOnThrottle(api.getYearEntries(yearId, yearFilter, 1000, 0)));
            int yrStatus = yr != null ? yr.getStatus() : -1;
            String yrBody = null;
            try {
                if (yr != null) yrBody = yr.readEntity(String.class);
            } finally {
                if (yr != null) yr.close();
            }
            log.info("Expense " + expense.getUuid() + ": year query year=" + yearId + ", filter='" + yearFilter + "', status=" + yrStatus);
            log.debug("Year response body (truncated): " + truncate(yrBody, 800));
            if (!isSuccessStatus(yrStatus)) {
                log.warn("Expense " + expense.getUuid() + ": year query failed with status=" + yrStatus + "; leaving status unchanged");
                return SyncOutcome.ERROR;
            }

            boolean booked = hasAnyEntries(yrBody);
            if (booked) {
                applyAccountAndNotes(expense, yrBody, "booked");
                expenseService.updateStatus(expense, ExpenseService.STATUS_VERIFIED_BOOKED);
                resetSyncMissCountIfNeeded(expense);
                log.info("Expense " + expense.getUuid() + " marked VERIFIED_BOOKED (booked to ledger under accounting-years)");
                return SyncOutcome.SUCCESS;
            }

            // Rows already DELETED are only re-checked for resurrection (steps 1–2
            // above can flip them back when the voucher reappears). A still-missing
            // voucher is their EXPECTED state: no journal sweep (28 extra API calls
            // per row per night — prod carries ~220 recently-modified DELETED rows
            // in the selection window), no miss counter, and no deletion candidate
            // (queueing them would false-trip the circuit breaker every night).
            if (ExpenseService.STATUS_DELETED.equals(expense.getStatus())) {
                log.debug("Expense " + expense.getUuid() + ": already DELETED and voucher still absent; skipping sweep and deletion phase");
                return SyncOutcome.SUCCESS;
            }

            // 2.5) Not in the stored journal and not booked. The accountant moves
            // unbooked postings into NEW temporary journals around fiscal-year start
            // (so voucher numbering can restart) and moves them back once the old
            // period is booked — a legitimate, recurring workflow. A lookup pinned to
            // the stored journal number sees "not found" for every moved voucher
            // (2026-07-28 incident: 224 false DELETEs). So search EVERY journal
            // before even considering deletion; a hit self-heals the stored triple.
            List<Integer> journalNumbers = fetchJournalNumbers(api, retry, expense);
            if (journalNumbers == null) {
                return SyncOutcome.ERROR; // journals listing failed — absence not proven, leave unchanged
            }
            SweepResult sweep = findVoucherInAnyJournal(api, retry, expense, jn, journalFilter, journalNumbers);
            if (sweep == null) {
                return SyncOutcome.ERROR; // a sweep lookup failed — absence not proven, leave unchanged
            }
            if (sweep.journalNumber != null) {
                log.warn("Expense " + expense.getUuid() + ": voucher " + vn + " found in journal " + sweep.journalNumber
                        + " (stored journal was " + jn + "); self-healing journalnumber and marking VERIFIED_UNBOOKED");
                expense.setJournalnumber(sweep.journalNumber);
                applyAccountAndNotes(expense, sweep.body, "journal-sweep");
                // updateStatus persists journalnumber from the entity, so the heal lands with the status
                expenseService.updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
                resetSyncMissCountIfNeeded(expense);
                return SyncOutcome.SUCCESS;
            }

            // 2.6) Number-based identity failed entirely — the voucher may have been
            // RENUMBERED by a journal move (e-conomic reassigns voucher numbers on
            // every move, verified live 2026-07-30 in both directions). Vouchers
            // uploaded since then carry a durable "#<uuid8>" marker in their entry
            // text (see VoucherText); scan every journal for it and re-link.
            MarkerSweepResult markerSweep = findVoucherByMarker(api, retry, expense, journalNumbers);
            if (markerSweep == null) {
                return SyncOutcome.ERROR; // a marker-scan lookup failed — absence not proven
            }
            if (markerSweep.hits.size() > 1) {
                log.error("Expense " + expense.getUuid() + ": text marker " + VoucherText.markerFor(expense.getUuid())
                        + " found in " + markerSweep.hits.size() + " entries — cannot re-link safely, leaving unchanged for manual review");
                return SyncOutcome.ERROR;
            }
            if (markerSweep.hits.size() == 1) {
                MarkerHit hit = markerSweep.hits.get(0);
                if (amountsMatch(expense.getAmount(), hit.amount)) {
                    log.warn("Expense " + expense.getUuid() + ": re-linked by text marker — voucher moved/renumbered "
                            + jn + "/" + vn + " -> " + hit.journalNumber + "/" + hit.voucherNumber);
                    expense.setJournalnumber(hit.journalNumber);
                    expense.setVouchernumber(hit.voucherNumber);
                    applyAccountAndNotes(expense, hit.entryBody, "marker");
                    // updateStatus persists journalnumber + vouchernumber from the entity
                    expenseService.updateStatus(expense, ExpenseService.STATUS_VERIFIED_UNBOOKED);
                    resetSyncMissCountIfNeeded(expense);
                    return SyncOutcome.SUCCESS;
                }
                log.warn("Expense " + expense.getUuid() + ": text marker found in journal " + hit.journalNumber
                        + " voucher " + hit.voucherNumber + " but amount differs (expense=" + expense.getAmount()
                        + ", entry=" + hit.amount + "); NOT re-linking");
            }

            // 3) Absent from EVERY journal and not booked. Grace period: only consider
            // deletion after N consecutive missing runs — a voucher mid-move between
            // journals reappears and the counter resets, so it is never wrongly deleted.
            int missCount = expense.getSafeSyncMissCount() + 1;
            if (missCount < syncDeleteMissThreshold) {
                expenseService.updateSyncMissCount(expense, missCount);
                log.warn("Expense " + expense.getUuid() + ": voucher not found in any journal or accounting-years; "
                        + "miss " + missCount + "/" + syncDeleteMissThreshold + " — grace period, NOT deleting yet");
                return SyncOutcome.SUCCESS;
            }
            // Threshold reached — queue for the end-of-run deletion phase. The counter is
            // deliberately NOT persisted here: if the circuit breaker refuses the batch,
            // the row keeps its pre-run state and the alert repeats nightly until a human acts.
            log.warn("Expense " + expense.getUuid() + ": voucher not found in any journal or accounting-years "
                    + "(miss " + missCount + "/" + syncDeleteMissThreshold + "); queueing for deletion pending circuit-breaker check");
            deletionCandidates.add(expense);
            return SyncOutcome.SUCCESS;
        } catch (EconomicsRateLimitException rle) {
            // Retries already exhausted inside executeWithRetry — count as throttled
            // so the circuit breaker can abort the run if this keeps happening.
            log.warn("Sync throttled (429) for expense " + expense.getUuid() + " after retries: " + rle.getMessage());
            return SyncOutcome.THROTTLED;
        } catch (Exception ex) {
            log.error("Sync failed for expense " + expense.getUuid() + ": " + ex.getMessage(), ex);
            return SyncOutcome.ERROR;
        }
    }

    /**
     * End-of-run deletion phase (#3, mass-deletion circuit breaker). Applies the
     * queued DELETEs only when the batch stays within the configured caps;
     * otherwise applies NONE of them and raises an alert — any bad night becomes
     * "0 deleted + 1 alert" instead of mass data damage.
     */
    void applyDeletionPhase(List<Expense> deletionCandidates, int totalSelected) {
        if (deletionCandidates.isEmpty()) {
            return;
        }
        DeletionCircuitBreaker deleteBreaker = new DeletionCircuitBreaker(
                syncDeleteAbortThreshold, syncDeleteAbortPercent, totalSelected);
        if (deleteBreaker.exceedsCap(deletionCandidates.size())) {
            // ERROR on purpose: the log-based production monitoring alerts on it (no
            // Slack path exists in this module). A tripped breaker means a human must
            // check e-conomic before any deletion happens; the rows keep their state
            // and the alert repeats every night until someone acts.
            String sampleUuids = deletionCandidates.stream()
                    .limit(10)
                    .map(Expense::getUuid)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            log.error("EXPENSE-SYNC DELETION CIRCUIT BREAKER TRIPPED: " + deletionCandidates.size()
                    + " of " + totalSelected + " selected expenses would be marked DELETED"
                    + " (absolute-threshold=" + syncDeleteAbortThreshold
                    + ", percent-threshold=" + syncDeleteAbortPercent + "%)."
                    + " NO expenses were deleted; all rows left unchanged. Manual review required —"
                    + " likely an e-conomic journal reshuffle (see 2026-07-28 incident)."
                    + " First candidate uuids: " + sampleUuids);
            return;
        }
        for (Expense expense : deletionCandidates) {
            log.warn("Expense " + expense.getUuid() + ": voucher not found in any journal or accounting-years for "
                    + syncDeleteMissThreshold + "+ consecutive runs; marking DELETED");
            // Counter reset FIRST (separate tx): if this row is ever manually restored,
            // it gets a full grace period again instead of being re-deleted on the next miss.
            expenseService.updateSyncMissCount(expense, 0);
            expenseService.updateStatus(expense, ExpenseService.STATUS_DELETED);
        }
    }

    /** Outcome of the all-journals sweep: which journal (if any) holds the voucher, and its entries body. */
    static final class SweepResult {
        final Integer journalNumber; // null = confirmed absent from every journal
        final String body;

        SweepResult(Integer journalNumber, String body) {
            this.journalNumber = journalNumber;
            this.body = body;
        }
    }

    /**
     * Fetches the tenant's journal numbers for the sweeps. Returns {@code null}
     * when the listing call failed — absence is then NOT provable and the caller
     * must leave the expense unchanged.
     */
    private List<Integer> fetchJournalNumbers(EconomicsAPI api, EconomicsRetryExecutor retry, Expense expense) {
        Response js = retry.executeWithRetry(() -> failOnThrottle(api.getJournals(JOURNALS_PAGESIZE)));
        int jsStatus = js != null ? js.getStatus() : -1;
        String jsBody = null;
        try {
            if (js != null) jsBody = js.readEntity(String.class);
        } finally {
            if (js != null) js.close();
        }
        if (!isSuccessStatus(jsStatus)) {
            log.warn("Expense " + expense.getUuid() + ": journals listing failed with status=" + jsStatus
                    + "; cannot conclude deletion, leaving status unchanged");
            return null;
        }
        return extractJournalNumbers(jsBody);
    }

    /**
     * Searches every journal of the expense's tenant (except the already-checked
     * stored one) for the voucher number. Returns a {@link SweepResult} with the
     * journal number on a hit, a SweepResult with {@code journalNumber == null}
     * when the voucher is confirmed absent from EVERY journal, or {@code null}
     * when any lookup failed — in which case absence is NOT proven and the caller
     * must leave the expense unchanged. Throttling propagates as
     * {@link EconomicsRateLimitException} exactly like the other lookups.
     */
    private SweepResult findVoucherInAnyJournal(EconomicsAPI api, EconomicsRetryExecutor retry,
                                                Expense expense, int storedJournal, String journalFilter,
                                                List<Integer> journalNumbers) {
        log.debug("Expense " + expense.getUuid() + ": sweeping " + journalNumbers.size() + " journals for voucher");
        for (Integer candidate : journalNumbers) {
            if (candidate == null || candidate == storedJournal) continue;
            Response cr = retry.executeWithRetry(() -> failOnThrottle(api.getJournalEntries(candidate, journalFilter, 1000)));
            int crStatus = cr != null ? cr.getStatus() : -1;
            String crBody = null;
            try {
                if (cr != null) crBody = cr.readEntity(String.class);
            } finally {
                if (cr != null) cr.close();
            }
            if (crStatus == 404) {
                continue; // journal disappeared between listing and lookup — nothing in it
            }
            if (!isSuccessStatus(crStatus)) {
                log.warn("Expense " + expense.getUuid() + ": journal sweep query failed for journal " + candidate
                        + " with status=" + crStatus + "; cannot conclude deletion, leaving status unchanged");
                return null;
            }
            if (hasAnyEntries(crBody)) {
                return new SweepResult(candidate, crBody);
            }
        }
        return new SweepResult(null, null);
    }

    /** One entry carrying the expense's text marker: its location, amount and a single-entry body. */
    static final class MarkerHit {
        final int journalNumber;
        final int voucherNumber;
        final Double amount;
        final String entryBody; // synthetic {"collection":[entry]} for applyAccountAndNotes

        MarkerHit(int journalNumber, int voucherNumber, Double amount, String entryBody) {
            this.journalNumber = journalNumber;
            this.voucherNumber = voucherNumber;
            this.amount = amount;
            this.entryBody = entryBody;
        }
    }

    /** Outcome of the marker sweep: all entries whose text carries the expense's marker. */
    static final class MarkerSweepResult {
        final List<MarkerHit> hits;

        MarkerSweepResult(List<MarkerHit> hits) {
            this.hits = hits;
        }
    }

    /** Safety cap: at most 10 pages (10k entries) scanned per journal. */
    static final int MARKER_SCAN_MAX_PAGES = 10;

    /**
     * Scans every journal's entries for the expense's "#&lt;uuid8&gt;" text marker
     * (see {@link VoucherText}) — the identity that survives the accountant's
     * journal moves, which reassign voucher numbers. Returns all hits (the caller
     * only re-links on an unambiguous single hit), or {@code null} when any page
     * fetch failed — absence is then NOT proven and the caller must leave the
     * expense unchanged. Vouchers uploaded before the marker existed simply never
     * match and fall through to the grace-period path.
     */
    private MarkerSweepResult findVoucherByMarker(EconomicsAPI api, EconomicsRetryExecutor retry,
                                                  Expense expense, List<Integer> journalNumbers) {
        String marker = VoucherText.markerFor(expense.getUuid());
        if (marker.isEmpty()) {
            return new MarkerSweepResult(new java.util.ArrayList<>());
        }
        List<MarkerHit> hits = new java.util.ArrayList<>();
        for (Integer candidate : journalNumbers) {
            if (candidate == null) continue;
            for (int page = 0; page < MARKER_SCAN_MAX_PAGES; page++) {
                final int skippages = page;
                Response pr = retry.executeWithRetry(() -> failOnThrottle(api.getJournalEntriesPage(candidate, 1000, skippages)));
                int prStatus = pr != null ? pr.getStatus() : -1;
                String prBody = null;
                try {
                    if (pr != null) prBody = pr.readEntity(String.class);
                } finally {
                    if (pr != null) pr.close();
                }
                if (prStatus == 404) {
                    break; // journal disappeared between listing and scan — nothing in it
                }
                if (!isSuccessStatus(prStatus)) {
                    log.warn("Expense " + expense.getUuid() + ": marker scan failed for journal " + candidate
                            + " page " + page + " with status=" + prStatus + "; cannot conclude, leaving status unchanged");
                    return null;
                }
                JsonNode entries = entriesArray(prBody);
                if (entries == null || entries.size() == 0) {
                    break;
                }
                for (JsonNode entry : entries) {
                    JsonNode textNode = entry.get("text");
                    if (textNode == null || !VoucherText.containsMarker(textNode.asText(), expense.getUuid())) {
                        continue;
                    }
                    JsonNode voucherNode = entry.get("voucher");
                    JsonNode vnNode = voucherNode != null ? voucherNode.get("voucherNumber") : null;
                    if (vnNode == null || !vnNode.canConvertToInt()) {
                        continue;
                    }
                    JsonNode amountNode = entry.get("amount");
                    Double amount = amountNode != null && amountNode.isNumber() ? amountNode.asDouble() : null;
                    hits.add(new MarkerHit(candidate, vnNode.asInt(), amount,
                            "{\"collection\":[" + entry.toString() + "]}"));
                }
                if (entries.size() < 1000) {
                    break; // last page
                }
            }
        }
        return new MarkerSweepResult(hits);
    }

    /** Belt-and-braces check on a marker hit: entry amount must equal the expense amount (sign-agnostic). */
    static boolean amountsMatch(Double expenseAmount, Double entryAmount) {
        if (expenseAmount == null || entryAmount == null) return false;
        return Math.abs(Math.abs(expenseAmount) - Math.abs(entryAmount)) < 0.005;
    }

    /** The entries array of a journal/year entries body (array, collection or items shape). */
    private static JsonNode entriesArray(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null) return null;
            if (root.isArray()) return root;
            if (root.has("collection") && root.get("collection").isArray()) return root.get("collection");
            if (root.has("items") && root.get("items").isArray()) return root.get("items");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Mirrors e-conomic's current account and accountant text onto the entity (persisted by updateStatus). */
    private void applyAccountAndNotes(Expense expense, String body, String source) {
        String accountFromEntries = extractFirstAccount(body);
        if (accountFromEntries != null && !accountFromEntries.equals(expense.getAccount())) {
            log.info("Expense " + expense.getUuid() + ": account differs (" + source + "); updating "
                    + expense.getAccount() + " -> " + accountFromEntries);
            expense.setAccount(accountFromEntries);
        }
        String textFromEntries = extractFirstText(body);
        if (textFromEntries != null && !textFromEntries.isEmpty()) {
            String currentNotes = expense.getAccountantNotes();
            if (currentNotes == null || !currentNotes.equals(textFromEntries)) {
                log.info("Expense " + expense.getUuid() + ": updating accountant notes (" + source + "): "
                        + truncate(textFromEntries, 80));
                expense.setAccountantNotes(textFromEntries);
            }
        }
    }

    /** The voucher was found again — clear any accumulated grace-period misses. */
    private void resetSyncMissCountIfNeeded(Expense expense) {
        if (expense.getSafeSyncMissCount() != 0) {
            log.info("Expense " + expense.getUuid() + ": voucher found again; resetting sync miss count from "
                    + expense.getSafeSyncMissCount());
            expenseService.updateSyncMissCount(expense, 0);
        }
    }

    /** Journal numbers from a GET /journals body (same array/collection/items shapes as hasAnyEntries). */
    static List<Integer> extractJournalNumbers(String body) {
        List<Integer> result = new java.util.ArrayList<>();
        if (body == null || body.isEmpty()) return result;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode arr = null;
            if (root.isArray()) {
                arr = root;
            } else if (root.has("collection") && root.get("collection").isArray()) {
                arr = root.get("collection");
            } else if (root.has("items") && root.get("items").isArray()) {
                arr = root.get("items");
            }
            if (arr == null) return result;
            for (JsonNode item : arr) {
                JsonNode n = item.get("journalNumber");
                if (n != null && n.canConvertToInt()) {
                    result.add(n.asInt());
                }
            }
        } catch (Exception e) {
            // Unparseable listing → empty list; the caller then falls through to the
            // grace-period counter, which still prevents any first-miss deletion.
        }
        return result;
    }

    static Response failOnThrottle(Response response) {
        if (response == null || response.getStatus() != 429) {
            return response;
        }
        Long retryAfterSeconds = EconomicsErrorMapper.parseRetryAfterSeconds(response.getHeaderString("Retry-After"));
        String body = null;
        try {
            body = response.readEntity(String.class);
        } catch (Exception ignore) {
        } finally {
            response.close();
        }
        throw new EconomicsRateLimitException("HTTP 429 from Economics: " + (body != null ? body : ""), retryAfterSeconds);
    }

    private static boolean isSuccessStatus(int status) {
        return status >= 200 && status < 300;
    }

    private boolean hasAnyEntries(String body) {
        if (body == null || body.isEmpty()) return false;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null) return false;
            if (root.isArray()) return root.size() > 0;
            if (root.has("collection") && root.get("collection").isArray()) return root.get("collection").size() > 0;
            if (root.has("items") && root.get("items").isArray()) return root.get("items").size() > 0;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractFirstAccount(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode first = null;
            if (root.isArray()) {
                first = root.size() > 0 ? root.get(0) : null;
            } else if (root.has("collection") && root.get("collection").isArray()) {
                JsonNode arr = root.get("collection");
                first = arr.size() > 0 ? arr.get(0) : null;
            } else if (root.has("items") && root.get("items").isArray()) {
                JsonNode arr = root.get("items");
                first = arr.size() > 0 ? arr.get(0) : null;
            }
            if (first == null) return null;
            // account.accountNumber may be nested
            JsonNode account = first.get("account");
            if (account != null && account.get("accountNumber") != null) {
                return account.get("accountNumber").asText();
            }
            // Alternative nested structure: entry.account.accountNumber
            if (first.has("entry") && first.get("entry").has("account") && first.get("entry").get("account").has("accountNumber")) {
                return first.get("entry").get("account").get("accountNumber").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFirstText(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode first = null;
            // Handle different response structures (same as extractFirstAccount)
            if (root.isArray()) {
                first = root.size() > 0 ? root.get(0) : null;
            } else if (root.has("collection") && root.get("collection").isArray()) {
                JsonNode arr = root.get("collection");
                first = arr.size() > 0 ? arr.get(0) : null;
            } else if (root.has("items") && root.get("items").isArray()) {
                JsonNode arr = root.get("items");
                first = arr.size() > 0 ? arr.get(0) : null;
            }
            if (first == null) return null;

            // Extract text field from entry. The app's "#<uuid8>" identity marker is
            // stripped so it never reaches accountantNotes / the intranet UI.
            JsonNode textNode = first.get("text");
            if (textNode != null && !textNode.isNull() && !textNode.asText().isEmpty()) {
                return VoucherText.stripMarker(textNode.asText());
            }

            // Alternative nested structure: entry.text
            if (first.has("entry") && first.get("entry").has("text")) {
                JsonNode entryText = first.get("entry").get("text");
                if (entryText != null && !entryText.isNull() && !entryText.asText().isEmpty()) {
                    return VoucherText.stripMarker(entryText.asText());
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("Failed to extract text from response: " + e.getMessage());
            return null;
        }
    }
}

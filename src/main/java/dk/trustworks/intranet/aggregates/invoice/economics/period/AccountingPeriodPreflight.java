package dk.trustworks.intranet.aggregates.invoice.economics.period;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Refuses a finalization whose invoice date lands in a period the issuer's e-conomic has closed or
 * barred, <em>before</em> any draft is created.
 *
 * <h2>Why it exists</h2>
 * On 2026-08-27 five internal invoices were force-created against Trustworks Cyber Security ApS,
 * whose e-conomic has every period of FY 2026/27 barred. Each attempt created a real e-conomic
 * draft, failed at the booking call with {@code E04870}, and rolled back — losing the local draft
 * number and stranding the draft at the vendor with nothing referencing it. Three drafts were
 * orphaned that way and the nightly batchlet would have added one per night indefinitely.
 *
 * <p>Every one of those consequences flows from discovering the barred period only at the booking
 * call, which is the last step. Asking first costs one GET and makes the whole failure disappear:
 * no draft, no rollback, no orphan, no burned idempotency key, and an error that names the fix.
 *
 * <h2>Fail-open is a requirement, not a shortcut</h2>
 * This check is a courtesy, and e-conomic remains the only authority on whether a posting is
 * allowed. It therefore blocks ONLY when it positively identifies a period that contains the date
 * and is flagged closed or barred. Every other outcome — the API erroring, timing out, returning
 * nothing, returning dates it cannot parse, or simply having no period covering the date — allows
 * the finalization to proceed and lets the vendor decide.
 *
 * <p>That asymmetry is deliberate. A false block would halt legitimate invoicing across all three
 * companies over a vendor hiccup; a missed block merely returns us to the pre-existing behaviour,
 * which now fails cleanly anyway thanks to the barred-period error mapping and the draft cleanup in
 * {@code InvoiceFinalizationOrchestrator}. Those two remain the backstop and must not be removed on
 * the strength of this check.
 */
@JBossLog
@ApplicationScoped
public class AccountingPeriodPreflight {

    /** e-conomic caps pageSize at 100. Agreements hold ~12 periods per accounting year. */
    private static final int PAGE_SIZE = 100;

    /**
     * Stop after this many pages. 1000 periods is ~83 accounting years — far beyond any real
     * agreement, so hitting it means something is wrong with paging and we should stop reading
     * rather than loop. Reaching the cap never blocks: an unfound period always falls open.
     */
    private static final int MAX_PAGES = 10;

    @Inject
    @RestClient
    EconomicsAccountingYearsApiClient periodsApi;

    @Inject
    EconomicsAgreementResolver agreements;

    /**
     * Kill switch. The check adds one synchronous vendor call to every finalization, so it needs a
     * way off that does not require a code change. Disabling it restores exactly the pre-2026-08-27
     * behaviour: the barred period is then discovered at the booking call instead.
     */
    @ConfigProperty(name = "dk.trustworks.invoice.period-preflight.enabled", defaultValue = "true")
    boolean preflightEnabled;

    /** What e-conomic says about posting on a given date, including "could not find out". */
    public enum PeriodState {
        /** A covering period was read and it is neither closed nor barred. */
        OPEN,
        /** A covering period was read and it refuses postings. */
        BLOCKED,
        /**
         * No answer: the API failed, or no period covers the date. Read as "don't block" by the
         * guard and as "don't back-date" by the date chooser — opposite readings, but each is the
         * conservative one for its caller.
         */
        UNKNOWN
    }

    /** A classification plus the period it came from, so messages can name what is wrong. */
    public record Verdict(PeriodState state, EconomicsAccountingPeriod period) {
        static Verdict unknown() { return new Verdict(PeriodState.UNKNOWN, null); }
    }

    /**
     * True only when a covering period was actually read and accepts postings.
     *
     * <p>For choosing a date rather than validating one. Returns {@code false} on UNKNOWN, so a
     * caller asking "may I date this into that period?" falls back to today rather than betting an
     * unbookable date on a vendor that did not answer.
     */
    public boolean isKnownOpen(String companyUuid, LocalDate date) {
        if (!preflightEnabled || companyUuid == null || date == null) return false;
        return verdictFor(companyUuid, date).state() == PeriodState.OPEN;
    }

    /**
     * True only when a covering period was read for <em>every</em> supplied date and all of them
     * refuse postings.
     *
     * <p>For a caller that would otherwise try several dates in turn — the expense voucher POST
     * walks its entry date forward a day at a time to get out from under a barred period. Asking
     * about one date is not enough there: e-conomic bars <em>periods</em>, so a barred August
     * inside an open FY 2026/27 is exactly the case that walk is built to escape, and refusing on
     * the strength of today's period alone would delete its only working outcome. Only when every
     * date it would try is provably blocked is the walk pointless.
     *
     * <p>The agreement's periods are read once, so the answer costs the same single vendor call
     * regardless of how many dates are asked about.
     *
     * <p>Fail-open like the rest of this class, and for the same reason: a single UNKNOWN or OPEN
     * date returns {@code false}, and so do a vendor failure and the kill switch. The caller then
     * proceeds and lets e-conomic decide, exactly as it did before this method existed.
     */
    public boolean allDatesBlocked(String companyUuid, Collection<LocalDate> dates) {
        Map<LocalDate, PeriodState> states = classifyDates(companyUuid, dates);
        return !states.isEmpty() && states.values().stream().allMatch(s -> s == PeriodState.BLOCKED);
    }

    /**
     * Classifies every supplied date against the agreement's periods, in the order given, using a
     * single vendor read.
     *
     * <p>For a caller that must both <em>choose</em> a date and know when there is nothing to
     * choose from — the expense voucher POST picks the earliest open period on or after the expense
     * date, and gives up when every candidate is blocked. Asking those two questions through
     * {@link #isKnownOpen} and {@link #allDatesBlocked} would read the agreement once per date plus
     * once more; this reads it once for both.
     *
     * <p>Fail-open like the rest of this class: the kill switch, a null company, a vendor failure
     * and a date no period covers all come back {@link PeriodState#UNKNOWN}, which every caller
     * must read as "let e-conomic decide". An empty or null input returns an empty map.
     *
     * @return an insertion-ordered map, one entry per distinct date, never null
     */
    public Map<LocalDate, PeriodState> classifyDates(String companyUuid, Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) return Map.of();

        LinkedHashMap<LocalDate, PeriodState> out = new LinkedHashMap<>();
        if (!preflightEnabled || companyUuid == null) {
            dates.forEach(d -> out.put(d, PeriodState.UNKNOWN));
            return out;
        }

        List<EconomicsAccountingPeriod> periods;
        try {
            periods = fetchPeriods(companyUuid);
        } catch (Exception e) {
            log.warnf("Period pre-flight unavailable for company=%s (%s: %s) — treating the dates "
                            + "as UNKNOWN; e-conomic remains the authority",
                    companyUuid, e.getClass().getSimpleName(), e.getMessage());
            dates.forEach(d -> out.put(d, PeriodState.UNKNOWN));
            return out;
        }
        dates.forEach(d -> out.put(d, classify(periods, d).state()));
        return out;
    }

    /**
     * Throws when the invoice's date provably cannot be posted in the issuer's e-conomic.
     *
     * @throws BadRequestException naming the company, the date, the reason and both remedies.
     */
    public void assertPeriodOpen(Invoice inv) {
        if (!preflightEnabled || inv == null || inv.getInvoicedate() == null
                || inv.getCompany() == null) {
            return;
        }
        String companyUuid = inv.getCompany().getUuid();
        String companyName = inv.getCompany().getName() != null
                ? inv.getCompany().getName() : companyUuid;
        LocalDate date = inv.getInvoicedate();

        Verdict verdict = verdictFor(companyUuid, date);
        if (verdict.state() != PeriodState.BLOCKED) return;

        EconomicsAccountingPeriod period = verdict.period();
        log.warnf("Period pre-flight REFUSED invoiceUuid=%s company=%s invoicedate=%s — period %s "
                        + "of year %s is %s",
                inv.getUuid(), companyName, date, period.getPeriodNumber(), period.getYear(),
                period.blockReason());

        throw new BadRequestException(String.format(
                "Invoice date %s falls in an accounting period that is %s in %s's e-conomic "
                        + "(period %s of %s), so it cannot be booked there. Either finalize it with "
                        + "a date in an open period, or open the period in e-conomic "
                        + "(Indstillinger → Regnskabsår → Perioder). This is per company, so the "
                        + "same date may well book in another Trustworks entity. Nothing was sent "
                        + "to e-conomic.",
                date, period.blockReason(), companyName,
                period.getPeriodNumber(), period.getYear()));
    }

    /** Reads the issuer's periods and classifies {@code date}; UNKNOWN whenever it cannot. */
    private Verdict verdictFor(String companyUuid, LocalDate date) {
        List<EconomicsAccountingPeriod> periods;
        try {
            periods = fetchPeriods(companyUuid);
        } catch (Exception e) {
            // Includes a missing or invalid agreement token, a vendor 4xx/5xx, and a timeout.
            log.warnf("Period pre-flight unavailable for company=%s (%s: %s) — treating the period "
                            + "as UNKNOWN; e-conomic remains the authority",
                    companyUuid, e.getClass().getSimpleName(), e.getMessage());
            return Verdict.unknown();
        }
        return classify(periods, date);
    }

    /**
     * Pure classification step, split out so the decision is unit-testable without a vendor.
     *
     * <p>A date covered by no period at all comes back UNKNOWN, not BLOCKED. e-conomic would refuse
     * it — the date is outside every defined accounting year — but that is indistinguishable from
     * an incomplete read (a short page, an unparseable bound), and the guard must never refuse an
     * invoice on something it cannot prove.
     */
    static Verdict classify(List<EconomicsAccountingPeriod> periods, LocalDate date) {
        if (periods == null || date == null) return Verdict.unknown();
        return periods.stream()
                .filter(p -> p.covers(date))
                .findFirst()
                .map(p -> new Verdict(p.blocksPosting() ? PeriodState.BLOCKED : PeriodState.OPEN, p))
                .orElseGet(Verdict::unknown);
    }

    /** Every accounting period in the agreement, paged. */
    private List<EconomicsAccountingPeriod> fetchPeriods(String companyUuid) {
        EconomicsAgreementResolver.Tokens tokens = agreements.tokens(companyUuid);
        List<EconomicsAccountingPeriod> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<EconomicsAccountingPeriod> batch =
                    periodsApi.listPeriods(tokens.appSecret(), tokens.agreementGrant(), PAGE_SIZE, page);
            if (batch == null || batch.isEmpty()) break;
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
        }
        return all;
    }
}

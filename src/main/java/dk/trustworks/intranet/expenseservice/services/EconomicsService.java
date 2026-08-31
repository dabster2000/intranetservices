package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.exceptions.ExpenseUploadException;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPIAccount;
import dk.trustworks.intranet.expenseservice.remote.EconomicsApiException;
import dk.trustworks.intranet.expenseservice.remote.EconomicsJournalsAPI;
import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.expenseservice.remote.DraftEntryDeleteRequest;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.*;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.ImageProcessor;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static dk.trustworks.intranet.financeservice.model.IntegrationKey.getIntegrationKeyValue;

@JBossLog
@RequestScoped
public class  EconomicsService {

    @Inject
    UserService userService;

    /**
     * Asked once before the voucher POST whether every entry date the auto-shift loop would try
     * is closed or barred, so a barred accounting year costs one GET instead of eight doomed
     * POSTs. Shared with the invoice path, which uses it for the same vendor state
     * ({@code InvoiceFinalizationOrchestrator}); it is fail-open, so it can only ever save work,
     * never refuse an expense e-conomic would have accepted.
     */
    @Inject
    AccountingPeriodPreflight periodPreflight;

    /**
     * Environment prefix on the idempotency key — prevents the same expense UUID
     * from colliding across environments at e-conomics' idempotency cache, which
     * would otherwise let staging block production's POST with HTTP 400 URLChanged.
     */
    @ConfigProperty(name = "dk.trustworks.environment.id", defaultValue = "production")
    String environmentId;

    /**
     * Cap on closed-period auto-shift retries. Covers the realistic edge cases (period
     * boundary race, year-end roll-over) without risking runaway loops.
     */
    private static final int MAX_PERIOD_SHIFT_DAYS = 7;

    /**
     * Ceiling on the back-dated candidates, so a nonsense expense date cannot turn one voucher POST
     * into an unbounded walk. Production's worst real submission lag is 442 days — about fifteen
     * month-firsts — so this leaves headroom over anything genuine while capping the absurd.
     */
    private static final int MAX_CANDIDATE_DATES = 24;

    /**
     * Builds the e-conomics Idempotency-Key header value for a voucher POST.
     * <p>Scoped to the target journal (URL = {@code /journals/{journalNumber}/vouchers}). e-conomic
     * rejects a reused key against a different URL with HTTP 400 "URLChanged" — which happens when an
     * expense is retried after its journal/company changed (e.g. an employee moved company). Including
     * the journal keeps retries to the same journal idempotent (no duplicate voucher) while letting a
     * different journal be treated as a fresh POST instead of failing.
     */
    String buildIdempotencyKey(Expense expense, int journalNumber) {
        if (expense.hasKnownCacheIssue() || Boolean.TRUE.equals(expense.getIsOrphaned())) {
            return String.format("%s-expense-%s-j%d-retry-%d",
                    environmentId, expense.getUuid(), journalNumber, expense.getSafeRetryCount());
        }
        return String.format("%s-expense-%s-j%d", environmentId, expense.getUuid(), journalNumber);
    }

    /**
     * Idempotency key used when retrying after a closed-period rejection. Distinct from
     * the standard and orphan keys so e-conomics' cache treats the shifted-date POST as a
     * fresh request.
     *
     * <p>Keyed on the entry date rather than on the attempt's position in the loop. The candidate
     * dates now depend on the expense date and on which periods e-conomic reports open, so the same
     * position means a different date on a different day — and reusing a key across two different
     * payloads is exactly what e-conomic's cache is entitled to collapse.
     */
    String buildPeriodShiftIdempotencyKey(Expense expense, LocalDate voucherDate) {
        return String.format("%s-expense-%s-period-shift-%s",
                environmentId, expense.getUuid(), voucherDate);
    }

    /**
     * e-conomic's code for "not found or barred" — <strong>ambiguous on its own</strong>.
     *
     * <p>The voucher endpoint returns E04041 for two unrelated conditions, told apart only by the
     * {@code propertyName} of the error node carrying it:
     * <ul>
     *   <li>{@code "Date"} → {@code "Perioden er spærret."} — the entry date is in a barred period,
     *       which shifting the date can escape;</li>
     *   <li>{@code "account"} → {@code "Account(s) is not found or barred."} — the chart-of-accounts
     *       account does not exist in that agreement, which no date can ever fix.</li>
     * </ul>
     *
     * <p>Matching the bare code cost us both. Production 2026-08-31: expense
     * 6258a643-fb69-4e41-894e-426cb7768ecc (Trustworks Technology ApS) was rejected because account
     * 3562 does not exist in TWT's e-conomic — verified against {@code /accounts/3562}, HTTP 404,
     * while TWT's FY2026/27 is open on all twelve periods. It still burned eight date-shifted POSTs
     * and then told Accounting to unbar a period that was never barred. Four more rows carry the
     * same misdiagnosis, three of them on the employee's <em>contra</em> account 9780.
     */
    static final String BARRED_ENTRY_DATE_CODE = "E04041";

    /** e-conomic's unambiguous "that account number does not exist" code. */
    private static final String ACCOUNT_NOT_FOUND_CODE = "E07150";

    /**
     * Markers meaning "this date is refused because its period is closed or barred", each of which
     * is unambiguous wherever it appears in the body.
     *
     * <p>Note e-conomic's two locks are independent: a period can be <em>Åben</em> with
     * <em>Spærret</em> ticked and still refuse postings, so "closed" tokens alone are not enough.
     *
     * <p>{@link #BARRED_ENTRY_DATE_CODE} is deliberately NOT in this list — it is ambiguous and is
     * only read as a barred period when {@code propertyName} says the offending property is a date.
     */
    private static final List<String> PERIOD_CLOSED_MARKERS = List.of(
            // Legacy name-style codes, kept so bodies that already matched still match.
            "AccountingYearClosed", "EntryDateInClosedPeriod", "DateInClosedPeriod",
            "PeriodClosed", "ClosedAccountingYear",
            // What the voucher endpoint says in prose, in both languages it uses.
            "Perioden er spærret", "barred in the accounting",
            // The invoice endpoint's equivalent (see InvoiceFinalizationOrchestrator): a
            // different code for the same condition, with the same remedy.
            "E04870", "barred period");

    /** {@code propertyName} values naming an account rather than a date. */
    private static final Set<String> ACCOUNT_PROPERTY_NAMES =
            Set.of("account", "contraaccount", "vataccount");

    /** JSON fields carrying an error signal, as opposed to the request input e-conomic echoes back. */
    private static final Set<String> ERROR_SIGNAL_FIELDS =
            Set.of("errorCode", "errorMessage", "developerHint", "message");

    /**
     * Why e-conomic refused a voucher, to the extent the body says.
     *
     * <p>The distinction is the whole point: only {@link #BARRED_PERIOD} is worth retrying on
     * another date, and only {@link #ACCOUNT_NOT_FOUND} should send anyone to the chart of accounts.
     */
    enum VoucherRejection {
        /** The entry date lies in a closed or barred period — a different date may work. */
        BARRED_PERIOD,
        /** An account in the payload does not exist in this agreement — no date will help. */
        ACCOUNT_NOT_FOUND,
        /** Anything else; the caller reports the vendor body as-is. */
        OTHER
    }

    /**
     * Classifies an e-conomic rejection body.
     *
     * <p>Walks the error tree and reads each error node together with its {@code propertyName},
     * because that pairing is the only thing separating a barred period from a missing account when
     * both arrive as {@link #BARRED_ENTRY_DATE_CODE}. A body carrying both is reported as
     * {@code ACCOUNT_NOT_FOUND}: the account is the blocker that no retry can clear.
     */
    static VoucherRejection classifyRejection(String body) {
        if (body == null || body.isBlank()) return VoucherRejection.OTHER;

        List<PropertyError> errors = propertyErrors(body);
        boolean barred = false;
        for (PropertyError e : errors) {
            if (e.isAccountNotFound()) return VoucherRejection.ACCOUNT_NOT_FOUND;
            if (e.isBarredPeriod()) barred = true;
        }
        if (barred) return VoucherRejection.BARRED_PERIOD;

        // Legacy name-style codes and the invoice endpoint's E04870: unambiguous wherever they sit.
        for (String signal : errorSignals(body)) {
            if (matchesAnyMarker(signal)) return VoucherRejection.BARRED_PERIOD;
        }

        // A body that names no property at all cannot be telling us it means an account, so the
        // ambiguity that forced the propertyName check above does not arise and the pre-2026-08-31
        // reading of a bare E04041 stands. Every shape production has actually produced carries a
        // propertyName and is decided before reaching here; this covers the shapes it has not.
        if (errors.isEmpty()) {
            for (String signal : errorSignals(body)) {
                if (signal.contains(BARRED_ENTRY_DATE_CODE)) return VoucherRejection.BARRED_PERIOD;
            }
        }
        return VoucherRejection.OTHER;
    }

    /**
     * Detects e-conomic error responses indicating the voucher's entry date falls in a closed or
     * barred accounting period.
     */
    boolean isPeriodClosedError(String body) {
        return classifyRejection(body) == VoucherRejection.BARRED_PERIOD;
    }

    private static boolean matchesAnyMarker(String signal) {
        for (String marker : PERIOD_CLOSED_MARKERS) {
            if (signal.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Whether a rejected voucher POST should be retried with the entry date shifted forward.
     *
     * <p>The one rule, shared by both ways a rejection reaches the loop: as a returned
     * {@code Response}, and — the path that actually occurs in production — as the exception
     * {@link dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper} throws in place
     * of one. Keeping it in a single method is what stops the two from drifting apart again.
     *
     * <p>Shifting is worth trying because e-conomic bars <em>periods</em>, not only whole years:
     * when the current month is barred and the next is open, a few days forward clears it. When
     * the whole accounting year is barred, no shift within {@link #MAX_PERIOD_SHIFT_DAYS} can
     * escape it and the loop ends at {@link #barredPeriodMessage} instead.
     *
     * <p>A missing account is explicitly NOT shiftable, however similar its error code looks —
     * see {@link #BARRED_ENTRY_DATE_CODE}.
     */
    boolean shouldShiftVoucherDate(int status, String body) {
        return status == 400 && classifyRejection(body) == VoucherRejection.BARRED_PERIOD;
    }

    /** How a rejected attachment POST can be recovered, if at all. */
    enum AttachmentRecovery {
        /** e-conomic refused the idempotency key ("URLChanged"); re-POST under a fresh one. */
        RETRY_NEW_IDEMPOTENCY_KEY,
        /** The voucher already carries an attachment; PATCH it instead of POSTing a second. */
        FALL_BACK_TO_PATCH,
        /** Nothing to recover from — the caller reports the failure. */
        NONE
    }

    /**
     * Reads e-conomic's rejection of an attachment POST and says which of the two recovery
     * paths applies.
     *
     * <p>Both keys off the 400 body, which is why this takes a status and a body rather than a
     * {@code Response}: {@link dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper}
     * is registered on {@link EconomicsAPI} and throws in place of returning one, so in practice
     * the pair arrives on an
     * {@link dk.trustworks.intranet.expenseservice.remote.EconomicsApiException}. Reading them
     * off a returned {@code Response} is what left both paths unreachable.
     */
    static AttachmentRecovery attachmentRecoveryFor(int status, String body) {
        if (status != 400 || body == null) return AttachmentRecovery.NONE;
        if (body.contains("URLChanged")) return AttachmentRecovery.RETRY_NEW_IDEMPOTENCY_KEY;
        if (body.contains("Voucher already has attachment")) return AttachmentRecovery.FALL_BACK_TO_PATCH;
        return AttachmentRecovery.NONE;
    }

    /**
     * Builds the exception for a rejection the date loop cannot retry, choosing the message by what
     * e-conomic actually objected to.
     *
     * <p>Reached when {@link #missingAccounts} could not prove the account missing — a vendor
     * hiccup, the account endpoint throttled — and the voucher POST then found out the hard way.
     * The pre-flight is a courtesy; this is the backstop that keeps the operator-facing message
     * right even when the courtesy is unavailable.
     */
    private ExpenseUploadException nonShiftableRejection(Expense expense, Company company,
                                                        UserAccount userAccount, Integer status,
                                                        String body, Exception cause,
                                                        String fallbackMessage) {
        if (classifyRejection(body) == VoucherRejection.ACCOUNT_NOT_FOUND) {
            log.errorf("Voucher rejected: account not found in %s's e-conomic. Expense uuid: %s, "
                            + "expense account: %s, contra account: %s, status: %s, body: %s",
                    company.getName(), expense.getUuid(), expense.getAccount(),
                    userAccount.getEconomics(), status, body);
            // errorDetails stays null for the same reason as the barred-period message: the row an
            // operator reads should carry the instruction, not the vendor's JSON.
            return new ExpenseUploadException(
                    accountNotFoundMessage(company.getName(), Integer.valueOf(expense.getAccount()),
                            userAccount.getEconomics(), List.of()),
                    cause, status, null);
        }
        log.error(fallbackMessage + ". Expense uuid: " + expense.getUuid()
                + ", status: " + status + ", details: " + body, cause);
        return new ExpenseUploadException(fallbackMessage, cause, status, body);
    }

    private static void closeQuietly(Response r) {
        if (r == null) return;
        try { r.close(); } catch (Exception ignore) { /* nothing useful to do */ }
    }

    /**
     * The error-bearing strings anywhere in an e-conomic problem body. The voucher endpoint buries
     * the real cause under {@code errors[].entries.items[].date.errors[]}, and that shape differs
     * per endpoint and per API generation, so this walks the tree instead of assuming one path.
     *
     * <p>Reading the error fields rather than the whole body also keeps the caller's own echoed
     * {@code inputValue} out of the match — a voucher text containing "PeriodClosed" is not a
     * closed period. Falls back to the raw body when it is not JSON or carries no recognisable
     * error field, so nothing that matched before can stop matching.
     */
    private static List<String> errorSignals(String body) {
        List<String> signals = new ArrayList<>();
        try {
            collectErrorSignals(new ObjectMapper().readTree(body), signals);
        } catch (Exception notJson) {
            return List.of(body);
        }
        return signals.isEmpty() ? List.of(body) : signals;
    }

    /**
     * One of e-conomic's leaf error objects, kept together with the property it blames.
     *
     * <p>{@code propertyName} arrives with inconsistent casing for the same property — the voucher
     * endpoint returns both {@code "account"} and {@code "Account"} in a single body — so every
     * comparison here is case-insensitive.
     */
    private record PropertyError(String propertyName, String signals) {

        boolean isBarredPeriod() {
            if (matchesAnyMarker(signals)) return true;
            return "date".equals(propertyName) && signals.contains(BARRED_ENTRY_DATE_CODE);
        }

        boolean isAccountNotFound() {
            if (!ACCOUNT_PROPERTY_NAMES.contains(propertyName)) return false;
            return signals.contains(ACCOUNT_NOT_FOUND_CODE)
                    || signals.contains(BARRED_ENTRY_DATE_CODE)
                    || signals.contains("not found");
        }
    }

    /**
     * Every error object in the body that names the property it blames, paired with its own error
     * signals.
     *
     * <p>Kept separate from {@link #errorSignals(String)} on purpose: that method flattens the tree
     * and throws the pairing away, which is exactly how a missing account came to be read as a
     * barred period. Reading {@code inputValue} is still avoided — a voucher text containing
     * "PeriodClosed" is not a closed period.
     */
    private static List<PropertyError> propertyErrors(String body) {
        List<PropertyError> out = new ArrayList<>();
        try {
            collectPropertyErrors(new ObjectMapper().readTree(body), out);
        } catch (Exception notJson) {
            return List.of();
        }
        return out;
    }

    private static void collectPropertyErrors(JsonNode node, List<PropertyError> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectPropertyErrors(child, out));
            return;
        }
        if (!node.isObject()) return;

        JsonNode propertyName = node.get("propertyName");
        if (propertyName != null && propertyName.isTextual()) {
            List<String> signals = new ArrayList<>();
            collectErrorSignals(node, signals);
            out.add(new PropertyError(
                    propertyName.asText().toLowerCase(Locale.ROOT), String.join("\n", signals)));
        }
        node.fields().forEachRemaining(field -> collectPropertyErrors(field.getValue(), out));
    }

    private static void collectErrorSignals(JsonNode node, List<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (value.isTextual() && ERROR_SIGNAL_FIELDS.contains(field.getKey())) {
                    out.add(value.asText());
                } else {
                    collectErrorSignals(value, out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectErrorSignals(child, out));
        }
    }

    /**
     * Rewrites an exhausted closed-period retry into an instruction the operator can act on.
     *
     * <p>Why not shift into an open year instead: the shift loop moves the entry date, and moving
     * it far enough to escape a barred year would post the expense into a different financial year
     * from the one it belongs to. Barring is a deliberate act by finance, not a race we may drive
     * around — for TWC in FY2026/27 the nearest unbarred window is 10 months out. A wrong date is
     * worse than a stopped pipeline, so the pipeline stops and says why.
     *
     * <p>The vendor body names the date and the year but never the agreement, which is the one fact
     * that matters: barring is per company, so the same date posts in one Trustworks entity and is
     * refused in the next. Same defect, and same fix, as
     * {@code InvoiceFinalizationOrchestrator.asBarredPeriodError}.
     */
    // Package-private static so it is directly unit-testable without booting Quarkus.
    static String barredPeriodMessage(String companyName, String accountingYear,
                                      LocalDate from, LocalDate to) {
        String company = (companyName == null || companyName.isBlank())
                ? "the employee's company" : companyName;
        String year = (accountingYear == null || accountingYear.isBlank())
                ? "the expense's accounting year" : accountingYear;
        return String.format(
                "Every accounting period from %s to %s is closed or barred in %s's e-conomic, so "
                        + "this expense cannot be posted on any date it belongs in "
                        + "(the current accounting year is %s). Have the periods unbarred in "
                        + "e-conomic (Indstillinger → Regnskabsår → Perioder), then requeue "
                        + "the expense. Barring is per company, so the same date may well post "
                        + "in another Trustworks entity. No voucher was created.",
                from, to, company, year);
    }

    /**
     * Rewrites a missing-account rejection into the only instruction that can actually fix it.
     *
     * <p>Separate from {@link #barredPeriodMessage} because the remedies have nothing in common:
     * one is a period setting, the other is the chart of accounts, and sending Accounting to the
     * wrong one of the two costs a day. The contra account is worth naming separately — it is the
     * employee's own e-conomic account from {@code UserAccount.economics}, so a miss there means a
     * stale employee mapping rather than a missing expense category, and three of the five rows
     * misdiagnosed in production were exactly that (account 9780).
     */
    static String accountNotFoundMessage(String companyName, Integer expenseAccount,
                                         Integer contraAccount, Collection<Integer> missing) {
        String company = (companyName == null || companyName.isBlank())
                ? "the employee's company" : companyName;
        StringBuilder which = new StringBuilder();
        if (missing != null && !missing.isEmpty()) {
            for (Integer account : missing) {
                if (which.length() > 0) which.append(" and ");
                which.append(account);
                if (account.equals(contraAccount)) which.append(" (the employee's contra account)");
                else if (account.equals(expenseAccount)) which.append(" (the expense account)");
            }
        } else {
            which.append("the expense account ").append(expenseAccount)
                 .append(" or the employee's contra account ").append(contraAccount);
        }
        return String.format(
                "Account %s does not exist in %s's e-conomic chart of accounts, so this expense "
                        + "cannot be posted on any date. Either create the account there or "
                        + "re-map the expense (an employee contra account comes from the user's "
                        + "e-conomic account number), then requeue the expense. Accounts are per "
                        + "company, so the same number may well exist in another Trustworks "
                        + "entity. No voucher was created.",
                which, company);
    }

    /**
     * The entry dates to try, earliest first: the expense's own date, then the first day of each
     * later month up to this one, then today and the {@link #MAX_PERIOD_SHIFT_DAYS} days after it.
     *
     * <p>Dating the voucher on {@code expensedate} is the point — the cost belongs to the period it
     * was incurred in, not to the night the batch happened to run. e-conomic then refuses anything
     * in a period finance has closed, which is precisely the control finance closes periods to
     * exert, and the candidates after it are the fallback: the earliest period that will still
     * accept the cost. Month-firsts rather than every intervening day because e-conomic bars whole
     * <em>periods</em> and periods are months — walking day by day asks the same question thirty
     * times. Production carries submissions up to 442 days late, so the walk has to be able to
     * cross a year, and roughly a dozen candidates is enough to do it.
     *
     * <p>The tail is capped at today + {@value #MAX_PERIOD_SHIFT_DAYS} deliberately. Without it,
     * "the first open period" for Trustworks Cyber Security ApS on 2026-08-31 is 2027-06-01 — ten
     * months of future-dated postings for costs already incurred. Beyond that cap there is no
     * defensible date left and the expense is parked for a human instead.
     */
    static List<LocalDate> candidateEntryDates(LocalDate expenseDate, LocalDate today) {
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        if (expenseDate != null && expenseDate.isBefore(today)) {
            dates.add(expenseDate);
            YearMonth current = YearMonth.from(today);
            for (YearMonth m = YearMonth.from(expenseDate).plusMonths(1);
                 !m.isAfter(current) && dates.size() < MAX_CANDIDATE_DATES;
                 m = m.plusMonths(1)) {
                LocalDate first = m.atDay(1);
                if (first.isBefore(today)) dates.add(first);
            }
        }
        for (int shift = 0; shift <= MAX_PERIOD_SHIFT_DAYS; shift++) dates.add(today.plusDays(shift));
        return List.copyOf(dates);
    }

    /**
     * Refuses, before any POST, an expense naming an account that does not exist in the company's
     * e-conomic.
     *
     * <p>The fact is already free: {@link #resolveDefaultVatCode} GETs the expense account one line
     * earlier and logs a WARN when it 404s, then posts anyway. This asks about the contra account
     * too and treats a definite 404 as the terminal condition it is.
     *
     * <p>Fail-open on everything else, for the same reason as the period pre-flight: only an actual
     * {@code 404} proves the account is missing. A timeout, a 5xx, a throttle or a thrown mapper
     * exception all fall through to the POST and let e-conomic decide. {@link EconomicsAPIAccount}
     * is the one client whose error mapper lets 404 through as a {@code Response} rather than
     * throwing, which is what makes the check possible at all.
     *
     * @return the account numbers proven missing, in payload order; empty when nothing is proven
     */
    List<Integer> missingAccounts(IntegrationKey.IntegrationKeyValue result,
                                  Integer expenseAccount, Integer contraAccount) {
        List<Integer> missing = new ArrayList<>();
        try (EconomicsAPIAccount accountApi = getEconomicsAccountAPI(result)) {
            for (Integer account : distinctAccounts(expenseAccount, contraAccount)) {
                if (accountIsProvenMissing(accountApi, account)) missing.add(account);
            }
        } catch (Exception e) {
            log.warnf("Account pre-flight unavailable (%s: %s) — letting e-conomic decide",
                    e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
        return missing;
    }

    private static List<Integer> distinctAccounts(Integer expenseAccount, Integer contraAccount) {
        LinkedHashSet<Integer> accounts = new LinkedHashSet<>();
        if (expenseAccount != null) accounts.add(expenseAccount);
        if (contraAccount != null) accounts.add(contraAccount);
        return List.copyOf(accounts);
    }

    private static boolean accountIsProvenMissing(EconomicsAPIAccount accountApi, int account) {
        try (Response r = accountApi.getAccount(account)) {
            return r.getStatus() == 404;
        } catch (Exception e) {
            // Any other rejection reaches us as a thrown mapper exception; not proof of absence.
            return false;
        }
    }

    public Response sendVoucher(Expense expense, ExpenseFile expensefile, UserAccount userAccount) throws Exception {
        log.info("Sending voucher for expense " + expense.getUuid());

        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        log.info("Voucher target = " + result);

        Journal journal = new Journal(result.expenseJournalNumber());
        // Name capped at 15 chars and a "#<uuid8>" marker appended — the marker is the
        // sync's durable identity for the voucher (numbers change on journal moves).
        String text = VoucherText.build(userAccount.getUsername(), expense.getAccountname(), expense.getUuid());
        Company company = getCompanyFromExpense(expense);

        if("44232855".equals(company.getCvr())) {
            expense.setAccount(String.valueOf(convertKontokode(Integer.parseInt(expense.getAccount()))));
        }
        String defaultVatCode = resolveDefaultVatCode(result, Integer.parseInt(expense.getAccount()));

        // Refuse an account that provably is not there before spending any POST on a date. A
        // missing account is not a period problem and no entry date can rescue it; posting anyway
        // is what produced eight doomed POSTs and an instruction to unbar a period that was open
        // (production 2026-08-31, TWT expense 6258a643…, account 3562).
        List<Integer> missingAccounts =
                missingAccounts(result, Integer.valueOf(expense.getAccount()), userAccount.getEconomics());
        if (!missingAccounts.isEmpty()) {
            log.errorf("Account pre-flight REFUSED expense %s before any POST — company: %s, "
                            + "missing account(s): %s (expense account %s, contra account %s)",
                    expense.getUuid(), company.getName(), missingAccounts,
                    expense.getAccount(), userAccount.getEconomics());
            throw new ExpenseUploadException(
                    accountNotFoundMessage(company.getName(), Integer.valueOf(expense.getAccount()),
                            userAccount.getEconomics(), missingAccounts),
                    null, null, null);
        }

        // One clock read for the whole method, so the dates the pre-flight is asked about are
        // exactly the ones the loop below will try.
        LocalDate today = LocalDate.now();
        List<LocalDate> candidateDates = candidateEntryDates(expense.getExpensedate(), today);

        // Ask before walking, and use the answer to CHOOSE rather than only to refuse. One read of
        // the agreement's periods gives both: which candidates e-conomic positively reports open,
        // and whether every one of them is blocked.
        //
        // Choosing matters because the candidates are ordered by accounting correctness, not by
        // convenience — expensedate first, then the earliest month that will still take the cost.
        // Posting the first KNOWN-OPEN one puts the expense in the period it belongs to and skips
        // the doomed POSTs in between.
        //
        // Fail-open is unchanged and load-bearing in both directions: UNKNOWN (a vendor hiccup, an
        // uncovered date, the kill switch) is neither open nor blocked, so it neither wins the
        // choice nor triggers the refusal, and we fall back to trying every candidate in order and
        // letting e-conomic decide — exactly the pre-existing behaviour.
        Map<LocalDate, AccountingPeriodPreflight.PeriodState> periodStates =
                periodPreflight.classifyDates(company.getUuid(), candidateDates);
        List<LocalDate> knownOpen = periodStates.entrySet().stream()
                .filter(e -> e.getValue() == AccountingPeriodPreflight.PeriodState.OPEN)
                .map(Map.Entry::getKey)
                .toList();
        List<LocalDate> attemptDates = knownOpen.isEmpty() ? candidateDates : knownOpen;

        if (knownOpen.isEmpty() && !periodStates.isEmpty()
                && periodStates.values().stream()
                        .allMatch(s -> s == AccountingPeriodPreflight.PeriodState.BLOCKED)) {
            String barredYear = DateUtils.getFiscalYearName(DateUtils.fiscalYearStart(today), company.getUuid());
            log.errorf("Period pre-flight REFUSED expense %s before any POST — company: %s, "
                            + "accountingYear: %s, every entry date %s..%s is closed or barred",
                    expense.getUuid(), company.getName(), barredYear,
                    candidateDates.get(0), candidateDates.get(candidateDates.size() - 1));
            throw new ExpenseUploadException(
                    barredPeriodMessage(company.getName(), barredYear,
                            candidateDates.get(0), candidateDates.get(candidateDates.size() - 1)),
                    null, null, null);
        }
        if (!knownOpen.isEmpty() && !knownOpen.get(0).equals(today)) {
            log.infof("Dating expense %s on %s — the earliest period e-conomic reports open at or "
                            + "after its expense date %s (company: %s)",
                    expense.getUuid(), knownOpen.get(0), expense.getExpensedate(), company.getName());
        }

        try (EconomicsAPI remoteApi = getEconomicsAPI(result)) {
            Voucher voucher = null;
            Response response = null;
            String lastBody = null;
            int lastStatus = 0;

            // Period-closed auto-shift: each retry moves the voucher entry date to the next
            // candidate period and uses a fresh idempotency key so e-conomic's cache treats it as
            // new. The first attempt is the accounting-correct one; the rest are the fallback.
            for (int attempt = 0; attempt < attemptDates.size(); attempt++) {
                LocalDate voucherDate = attemptDates.get(attempt);
                voucher = buildJSONRequestWithDate(expense, userAccount, journal, text, voucherDate, defaultVatCode);
                String json = new ObjectMapper().writeValueAsString(voucher);
                String idempotencyKey = (attempt == 0)
                        ? buildIdempotencyKey(expense, journal.getJournalNumber())
                        : buildPeriodShiftIdempotencyKey(expense, voucherDate);

                if (attempt == 0) {
                    log.debugf("Posting voucher for expense %s with idempotency key %s", expense.getUuid(), idempotencyKey);
                    log.info("Voucher payload = " + json);
                } else {
                    log.warnf("Closed period on previous attempt — retrying expense %s with voucher date %s (attempt %d of %d, key %s)",
                            expense.getUuid(), voucherDate, attempt + 1, attemptDates.size(), idempotencyKey);
                }

                try {
                    response = remoteApi.postVoucher(journal.getJournalNumber(), idempotencyKey, json);
                } catch (EconomicsApiException e) {
                    // EconomicsErrorMapper is registered on EconomicsAPI and turns every non-2xx
                    // except 404 into a thrown exception, so a rejected voucher never arrives as a
                    // returned Response — the status inspection below cannot see it. Before this
                    // branch existed, a barred-period 400 fell straight through to catch (Exception)
                    // and failed the expense outright, which is why the shift loop never ran in
                    // production (2026-08-30, expenses a84274cb… and ab414c17…, both TWC FY2026/27).
                    lastStatus = e.getStatus();
                    lastBody = e.getBody();
                    response = null;
                    if (shouldShiftVoucherDate(lastStatus, lastBody)) continue;
                    throw nonShiftableRejection(expense, company, userAccount, lastStatus, lastBody, e,
                            "Failed to post voucher to e-conomics");
                } catch (WebApplicationException e) {
                    String errorDetails = safeRead(e.getResponse());
                    log.error("Failed to post voucher to e-conomics. Expense uuid: " + expense.getUuid() + ", status: " + e.getResponse().getStatus() + ", details: " + errorDetails, e);
                    throw new ExpenseUploadException("Failed to post voucher to e-conomics", e, e.getResponse().getStatus(), errorDetails);
                } catch (Exception e) {
                    log.error("Failed to post voucher to e-conomics. Expense uuid: " + expense.getUuid() + ", voucher: " + voucher, e);
                    throw new ExpenseUploadException("Failed to post voucher to e-conomics", e, null, e.toString());
                }

                int status = response.getStatus();
                if (status >= 200 && status < 300) break; // success — fall through to response parsing
                lastStatus = status;
                lastBody = safeRead(response);
                try { response.close(); } catch (Exception ignore) {}
                response = null;
                if (!shouldShiftVoucherDate(status, lastBody)) {
                    throw nonShiftableRejection(expense, company, userAccount, status, lastBody, null,
                            "Voucher not posted successfully to e-conomics");
                }
            }
            if (response == null) {
                String barredYear = (voucher != null && voucher.getAccountingYear() != null)
                        ? voucher.getAccountingYear().getYear()
                        : expense.getAccountingyear();
                // The raw body stays HERE, in the log, where it is diagnostic. It deliberately does
                // not go on the expense record (errorDetails = null below): ExpenseService writes
                // getDetailedMessage() into the row an operator reads, and a wall of vendor JSON
                // told them neither which company had barred the year nor what to do about it.
                log.errorf("Voucher post failed after %d period-shift attempts. Expense uuid: %s, "
                                + "company: %s, accountingYear: %s, entry dates %s..%s, "
                                + "lastStatus: %d, lastBody: %s",
                        attemptDates.size(), expense.getUuid(), company.getName(), barredYear,
                        attemptDates.get(0), attemptDates.get(attemptDates.size() - 1),
                        lastStatus, lastBody);
                throw new ExpenseUploadException(
                        barredPeriodMessage(company.getName(), barredYear,
                                attemptDates.get(0), attemptDates.get(attemptDates.size() - 1)),
                        null, lastStatus, null);
            }

            try (Response voucherResponse = response) {
                ObjectMapper objectMapper = new ObjectMapper();
                String responseAsString = voucherResponse.readEntity(String.class);
                JsonNode root = objectMapper.readValue(responseAsString, JsonNode.class);
                JsonNode first = root.isArray() ? (!root.isEmpty() ? root.get(0) : null) : root;
                if (first == null || first.get("voucherNumber") == null) {
                    log.error("Unexpected voucher POST response: " + responseAsString);
                    throw new ExpenseUploadException("Unexpected voucher response from e-conomics", null, 502, responseAsString);
                }
                int voucherNumber = first.get("voucherNumber").asInt();
                expense.setVouchernumber(voucherNumber);
                // persist accountingYear (canonical URL form without any trailing letters) and journal too
                String fiscalYearName = voucher.getAccountingYear().getYear(); // e.g., 2025/2026 or 2025/2026a
                String urlYear = DateUtils.toEconomicsUrlYear(fiscalYearName);
                log.debugf("Storing accounting year for expense %s: fiscalYear=%s -> storedFormat=%s",
                    expense.getUuid(), fiscalYearName, urlYear);
                expense.setAccountingyear(urlYear);
                expense.setJournalnumber(journal.getJournalNumber());

                //upload file to e-conomics voucher
                return sendFile(expense, expensefile, voucher);
            }
        }
    }

    public Response sendFile(Expense expense, ExpenseFile expensefile, Voucher voucher) throws Exception {
        log.info("Uploading file for expense " + expense.getUuid());

        // DEFENSIVE CHECK: Verify voucher actually exists before attempting file upload
        // This prevents attempting to upload files to non-existent vouchers
        // (can happen if e-conomics returned cached success but voucher wasn't actually created).
        // Only a PROVEN 404 aborts: an indeterminate lookup (5xx/network) must not fail the
        // upload of a voucher that was just created — the attachment call itself surfaces
        // real errors, while a false abort here leads to an orphan-retry duplicate voucher.
        if (expense.getVouchernumber() > 0 && checkVoucherExists(expense) == VoucherLookupResult.NOT_FOUND) {
            String storedYear = expense.getAccountingyear();
            // Show the actual URL format used in verification (underscore format)
            String urlYear = DateUtils.toEconomicsUrlYear(storedYear);
            log.errorf("Voucher %d doesn't exist in e-conomics for expense %s - orphaned reference detected (storedYear=%s, urlYear=%s)",
                expense.getVouchernumber(), expense.getUuid(), storedYear, urlYear);
            throw new ExpenseUploadException(
                "Orphaned voucher detected - voucher exists in cache but not in e-conomics",
                null,
                404,
                String.format("Voucher %d not found in journal %d for year %s (urlYear=%s)",
                    expense.getVouchernumber(),
                    expense.getJournalnumber(),
                    storedYear,
                    urlYear)
            );
        }

        // Convert to URL format (underscore format) for API path parameters - same as invoice code
        String originalYear = voucher.getAccountingYear().getYear();
        final String urlYear = DateUtils.toEconomicsUrlYear(originalYear);
        log.debugf("File upload for expense %s: originalYear=%s -> urlYear=%s, voucher=%d",
            expense.getUuid(), originalYear, urlYear, expense.getVouchernumber());

        ImageProcessor.ReceiptAttachment attachment = ImageProcessor.prepareReceiptForUpload(expensefile.getExpensefile());
        byte[] bytes = attachment.bytes();
        if (bytes == null || bytes.length == 0) {
            throw new ExpenseUploadException("Empty attachment after receipt preparation", null, 400, "Prepared attachment is empty");
        }
        if (bytes.length > 9 * 1024 * 1024) {
            throw new ExpenseUploadException("Attachment too large", null, 413, "File size: " + bytes.length + " bytes (max 9MB)");
        }
        log.infof("Attaching receipt for expense %s as %s (%d bytes)", expense.getUuid(), attachment.mediaType(), bytes.length);

        MultipartFormDataOutput form = new MultipartFormDataOutput();
        form.addFormData("file", new ByteArrayInputStream(bytes), MediaType.valueOf(attachment.mediaType()), attachment.filename());

        try (EconomicsAPI api = getApiForExpense(expense)) {
            // 1) Check om der allerede er en vedhæftning
            boolean hasAttachment = false;
            try (Response meta = api.getAttachment(voucher.getJournal().getJournalNumber(), urlYear, expense.getVouchernumber())) {
                if (meta.getStatus() / 100 == 2) {
                    String json = meta.readEntity(String.class);
                    hasAttachment = json.contains("\"pages\"") && !json.contains("\"pages\":0");
                }
            } catch (Exception ignore) { /* fortsæt defensivt */ }

            // 2) POST hvis ingen vedhæftning, ellers PATCH
            try {
                // Smart idempotency for file upload: Use operation type and retry count
                // This maintains determinism while allowing retries when needed
                String operation = !hasAttachment ? "POST" : "PATCH";
                String idemp = String.format("attach-%s-%s-v%d",
                    expense.getUuid(), operation, expense.getSafeRetryCount());
                Response r;

                if (!hasAttachment) {
                    log.debug("POST file attachment: /journals/" + voucher.getJournal().getJournalNumber() +
                        "/vouchers/" + urlYear + "-" + expense.getVouchernumber() + "/attachment/file");
                    // EconomicsErrorMapper is registered on EconomicsAPI and turns every status
                    // >= 400 except 404 into a thrown exception, so e-conomic's rejection never
                    // arrives as a Response — a returned one here is 2xx, 3xx or 404, never the
                    // 400 both recovery paths key on. Reading the status off `r` is what left
                    // them unreachable, so take the pair from whichever the call produced.
                    int status;
                    String body;
                    try {
                        r = api.postExpenseFile(
                                voucher.getJournal().getJournalNumber(),
                                urlYear,
                                expense.getVouchernumber(),
                                idemp,
                                form
                        );
                        status = r.getStatus();
                        body = null;
                    } catch (EconomicsApiException e) {
                        r = null;
                        status = e.getStatus();
                        body = e.getBody();
                    }

                    AttachmentRecovery recovery = attachmentRecoveryFor(status, body);
                    // Check for idempotency key collision (URLChanged error)
                    if (recovery == AttachmentRecovery.RETRY_NEW_IDEMPOTENCY_KEY) {
                        log.warnf("Idempotency key conflict detected for expense %s - retrying with incremented version. Error: %s",
                                 expense.getUuid(), body);
                        // Close the failed response before retrying
                        closeQuietly(r);
                        // Increment retry count for new idempotency version
                        expense.incrementRetryCount();
                        String retryIdemp = String.format("attach-%s-POST-v%d",
                            expense.getUuid(), expense.getSafeRetryCount());
                        r = api.postExpenseFile(
                                voucher.getJournal().getJournalNumber(),
                                urlYear,
                                expense.getVouchernumber(),
                                retryIdemp,
                                form
                        );
                        log.info("Retry with new idempotency key completed, status: " + r.getStatus());
                    }
                    // Existing fallback for "Voucher already has attachment"
                    else if (recovery == AttachmentRecovery.FALL_BACK_TO_PATCH) {
                        log.infof("Voucher already has attachment, switching to PATCH for expense %s", expense.getUuid());
                        // Close the failed response before retrying
                        closeQuietly(r);
                        // fallback til PATCH with deterministic idempotency key
                        String patchIdemp = String.format("attach-%s-PATCH-v%d",
                            expense.getUuid(), expense.getSafeRetryCount());
                        r = api.patchFile(
                                voucher.getJournal().getJournalNumber(),
                                urlYear,
                                expense.getVouchernumber(),
                                patchIdemp,
                                form
                        );
                    }
                    // The mapper threw and neither recovery applies: there is no Response to fall
                    // through to, and the vendor's own status says more than the "unexpected"
                    // 502 this used to surface as.
                    else if (r == null) {
                        log.error("File upload failed for expense " + expense.getUuid() + ", status: " + status + ", details: " + body);
                        throw new ExpenseUploadException("File upload to e-conomics failed", null, status, body);
                    }
                } else {
                    log.debug("PATCH file attachment: /journals/" + voucher.getJournal().getJournalNumber() +
                        "/vouchers/" + urlYear + "-" + expense.getVouchernumber() + "/attachment/file");
                    r = api.patchFile(
                            voucher.getJournal().getJournalNumber(),
                            urlYear,
                            expense.getVouchernumber(),
                            idemp,
                            form
                    );
                }

                // Check if file upload was successful
                if (r.getStatus() < 200 || r.getStatus() >= 300) {
                    String errorDetails = safeRead(r);
                    log.error("File upload failed for expense " + expense.getUuid() + ", status: " + r.getStatus() + ", details: " + errorDetails);
                    throw new ExpenseUploadException("File upload to e-conomics failed", null, r.getStatus(), errorDetails);
                }

                return r;
            } catch (EconomicsApiException e) {
                // Covers the retry POST, the PATCH fallback and the PATCH-only branch above.
                // Without this the mapper's exception fell through to catch (Exception) below,
                // and every attachment rejection reached operators as a 502 gateway fault
                // instead of the status and body e-conomic actually sent.
                log.error("File upload failed for expense " + expense.getUuid() + ", status: " + e.getStatus() + ", details: " + e.getBody(), e);
                throw new ExpenseUploadException("File upload to e-conomics failed", e, e.getStatus(), e.getBody());
            } catch (WebApplicationException wae) {
                String errorDetails = safeRead(wae.getResponse());
                log.error("WebApplicationException during file upload for expense " + expense.getUuid() + ", status: " + wae.getResponse().getStatus() + ", details: " + errorDetails);
                throw new ExpenseUploadException("File upload to e-conomics failed", wae, wae.getResponse().getStatus(), errorDetails);
            } catch (ExpenseUploadException e) {
                // Re-throw our custom exceptions
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error when posting file for expense " + expense.getUuid(), e);
                throw new ExpenseUploadException("Unexpected error during attachment upload", e, 502, e.getMessage());
            }
        }
    }

    public Voucher buildJSONRequest(Expense expense, UserAccount userAccount, Journal journal, String text){
        return buildJSONRequestWithDate(expense, userAccount, journal, text, LocalDate.now());
    }

    /**
     * Builds the voucher JSON payload with an explicit entry date. The accounting year
     * is derived from this date (not today's date) so closed-period auto-shifts that
     * cross the fiscal year boundary land in the correct year.
     */
    public Voucher buildJSONRequestWithDate(Expense expense, UserAccount userAccount, Journal journal, String text, LocalDate voucherDate){
        return buildJSONRequestWithDate(expense, userAccount, journal, text, voucherDate, null);
    }

    public Voucher buildJSONRequestWithDate(Expense expense, UserAccount userAccount, Journal journal, String text, LocalDate voucherDate, String vatCode){
        Company company = getCompanyFromExpense(expense);
        String fiscalYearName = DateUtils.getFiscalYearName(DateUtils.fiscalYearStart(voucherDate), company.getUuid());
        AccountingYear accountingYear = new AccountingYear(fiscalYearName);
        log.debug("Using accounting year " + fiscalYearName + " for company " + company.getUuid());

        String date = voucherDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        FinanceVoucher financeVoucher1 = buildFinanceVoucher(expense, userAccount, text, date, vatCode);
        List<FinanceVoucher> financeVouchers = new ArrayList<>();
        financeVouchers.add(financeVoucher1);

        Entries entries = new Entries();
        Voucher voucher = new Voucher(accountingYear, journal, entries);

        entries.setFinanceVouchers(financeVouchers);
        voucher.setEntries(entries);

        return voucher;
    }

    FinanceVoucher buildFinanceVoucher(Expense expense, UserAccount userAccount, String text, String date, String vatCode) {
        ContraAccount contraAccount = new ContraAccount(userAccount.getEconomics());
        ExpenseAccount expenseaccount = new ExpenseAccount(Integer.parseInt(expense.getAccount()));
        FinanceVoucher financeVoucher = new FinanceVoucher(expenseaccount, text, expense.getAmount(), contraAccount, date);
        if (vatCode != null && !vatCode.isBlank()) {
            financeVoucher.setVatAccount(new VatAccount(vatCode.trim()));
        }
        return financeVoucher;
    }

    public Boolean validateAccount(Expense expense) {
        int account = Integer.parseInt(expense.getAccount());
        try {
            //economicsAPIAccount.getAccount(account);
            return true;
        } catch (Exception e){
            return false;
        }
    }

    String resolveDefaultVatCode(IntegrationKey.IntegrationKeyValue result, int accountNumber) {
        try (EconomicsAPIAccount economicsAccountAPI = getEconomicsAccountAPI(result)) {
            try (Response accountResponse = economicsAccountAPI.getAccount(accountNumber)) {
                int status = accountResponse.getStatus();
                if (status < 200 || status >= 300) {
                    String body = safeRead(accountResponse);
                    log.warnf("Could not resolve e-conomic VAT default for account %d: status=%d, body=%s. Posting without VAT.",
                            accountNumber, status, body);
                    return null;
                }

                String response = accountResponse.readEntity(String.class);
                Optional<String> vatCode = extractDefaultVatCode(response);
                if (vatCode.isEmpty()) {
                    log.warnf("No e-conomic VAT default returned for account %d. Posting without VAT.", accountNumber);
                    return null;
                }
                return vatCode.get();
            }
        } catch (Exception e) {
            log.warnf(e, "Could not resolve e-conomic VAT default for account %d. Posting without VAT.", accountNumber);
            return null;
        }
    }

    static Optional<String> extractDefaultVatCode(String accountJson) {
        if (accountJson == null || accountJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode vatCodeNode = new ObjectMapper()
                    .readTree(accountJson)
                    .path("vatAccount")
                    .path("vatCode");
            if (vatCodeNode.isMissingNode() || vatCodeNode.isNull()) {
                return Optional.empty();
            }
            String vatCode = vatCodeNode.asText(null);
            if (vatCode == null || vatCode.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(vatCode.trim());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public String getAccount(String companyuuid, Integer account) throws Exception {
        // call e-conomics endpoint with proper resource management
        String response = null;
        try (EconomicsAPIAccount economicsAccountAPI = getEconomicsAccountAPI(getIntegrationKeyValue(Company.findById(companyuuid)))) {
            try (Response accountResponse = economicsAccountAPI.getAccount(account)) {
                response = accountResponse.readEntity(String.class);
            } catch (Exception e) {
                log.error("account = "+account);
                log.error(e.getMessage());
            }
        }
        if(response==null) return "";
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(response);
        return jsonNode.get("name").asText();
    }

    private static EconomicsAPI getEconomicsAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

    public EconomicsAPI getApiForExpense(Expense expense) {
        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        return getEconomicsAPI(result);
    }

    private static EconomicsAPIAccount getEconomicsAccountAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(result.url()))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsAPIAccount.class);
    }

    /**
     * Create Journals API client (NEW API for draft entry deletion).
     * Base URL: https://apis.e-conomic.com/journalsapi/v13.0.1
     */
    private static EconomicsJournalsAPI getJournalsAPI(IntegrationKey.IntegrationKeyValue result) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://apis.e-conomic.com/journalsapi/v13.0.1"))
                .register(new EconomicsDynamicHeaderFilter(result.appSecretToken(), result.agreementGrantToken()))
                .build(EconomicsJournalsAPI.class);
    }

    /**
     * Get entry details from journal to obtain entryNumber and objectVersion.
     * Required for deleting draft entries via NEW Journals API.
     *
     * @param expense Expense with voucher reference
     * @param integrationKey Integration keys for authentication
     * @return Entry details or null if not found
     */
    private JournalEntryResponse.Entry getEntryDetails(Expense expense, IntegrationKey.IntegrationKeyValue integrationKey) throws Exception {
        try (EconomicsJournalsAPI journalsAPI = getJournalsAPI(integrationKey)) {
            // Filter must include BOTH journalNumber AND voucherNumber for NEW Journals API
            String filter = String.format("journalNumber$eq:%d$and:voucherNumber$eq:%d",
                expense.getJournalnumber(), expense.getVouchernumber());
            log.infof("Fetching draft entries with filter: %s", filter);

            JournalEntryResponse response = journalsAPI.getDraftEntries(
                filter,
                null,  // cursor (optional, for pagination)
                1000   // pagesize
            );

            if (response.collection == null || response.collection.isEmpty()) {
                log.warnf("No draft entries found for filter: %s", filter);
                return null;
            }

            // Return first matching entry (expenses typically have one entry per voucher)
            JournalEntryResponse.Entry entry = response.collection.get(0);
            log.infof("Found draft entry: entryNumber=%d, voucherNumber=%d, objectVersion=%s",
                entry.entryNumber, entry.voucherNumber, entry.objectVersion);
            return entry;
        }
    }

    private IntegrationKey.IntegrationKeyValue getIntegrationKey(Expense expense) {
        Company company = getCompanyFromExpense(expense);

        return getIntegrationKeyValue(company);
    }

    private Company getCompanyFromExpense(Expense expense) {
        UserStatus userStatus = userService.findById(expense.getUseruuid(), false).getUserStatus(expense.getExpensedate());
        return userStatus.getCompany();
    }

    private static final Map<Integer, Integer> conversionMap = new HashMap<>();

    static {
        conversionMap.put(4003, 2800);
        conversionMap.put(4008, 2754);
        conversionMap.put(5218, 3604);
        conversionMap.put(5219, 3603);
        conversionMap.put(5214, 3605);
        conversionMap.put(4055, 2779);
        conversionMap.put(4030, 2770);
        conversionMap.put(4050, 2777);
        conversionMap.put(3560, 2245);
        conversionMap.put(3585, 2250);
        conversionMap.put(3591, 2260);
        conversionMap.put(4006, 2753);
        conversionMap.put(4020, 2720);
        conversionMap.put(5222, 3617);
        conversionMap.put(5233, 3600);
        conversionMap.put(5242, 3620);
        conversionMap.put(3575, 2258);
        conversionMap.put(5234, 2780);
        conversionMap.put(4007, 2781);
        conversionMap.put(4040, 2782);
        conversionMap.put(4042, 2783);
    }

    public static int convertKontokode(int kontokode) {
        // Check if the kontokode exists in the map and return the converted value.
        // If the kontokode does not exist in the map, return the input as is or handle as needed.
        return conversionMap.getOrDefault(kontokode, kontokode);
    }

    private static String safeRead(Response r) {
        if (r == null) return null;
        try {
            return r.readEntity(String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Outcome of a voucher lookup against e-conomic. {@code NOT_FOUND} is a PROVEN
     * absence (HTTP 404 / empty result on a successful call); {@code UNKNOWN} means
     * the lookup itself failed (5xx, network error, throttling) and the voucher's
     * state could not be determined. Callers must never treat UNKNOWN as absence:
     * doing so is what turned the 2026-08-12 e-conomic 503 outage into a wave of
     * false "orphaned voucher" marks and "MISSING" re-send prechecks.
     */
    public enum VoucherLookupResult { FOUND, NOT_FOUND, UNKNOWN }

    /** Seam for tests: the eventual-consistency retry delay in {@link #checkVoucherExists}. */
    long verifyRetrySleepMs = 1500L;

    /**
     * Checks whether the expense's stored voucher (journal/year/number triple) exists
     * as a draft in e-conomic. Missing triple counts as {@code NOT_FOUND} (there is
     * nothing to look up).
     */
    public VoucherLookupResult checkVoucherExists(Expense expense) {
        if (expense.getVouchernumber() <= 0 ||
            expense.getJournalnumber() == null ||
            expense.getAccountingyear() == null) {
            return VoucherLookupResult.NOT_FOUND;
        }
        try (EconomicsAPI api = getApiForExpense(expense)) {
            return checkVoucherExists(expense, api);
        } catch (Exception e) {
            log.error("Error verifying voucher existence for expense " + expense.getUuid(), e);
            return VoucherLookupResult.UNKNOWN;
        }
    }

    /** Lookup body of {@link #checkVoucherExists(Expense)}; package-private so tests can inject the API. */
    VoucherLookupResult checkVoucherExists(Expense expense, EconomicsAPI api) {
        // Convert stored year to e-conomics URL format (underscore format)
        String storedYear = expense.getAccountingyear();
        String urlYear = DateUtils.toEconomicsUrlYear(storedYear);

        log.debugf("Verifying voucher existence for expense %s: journal=%d, storedYear=%s -> urlYear=%s, voucher=%d",
            expense.getUuid(), expense.getJournalnumber(), storedYear, urlYear, expense.getVouchernumber());

        try {
            // Retry a few times in case of eventual consistency right after creation
            int attempts = 3;
            for (int i = 0; i < attempts; i++) {
                Response response = api.getVoucher(
                        expense.getJournalnumber(),
                        urlYear,
                        expense.getVouchernumber()
                );
                int status = response != null ? response.getStatus() : 0;

                log.debugf("Voucher verification response: status=%d for voucher %d", status, expense.getVouchernumber());

                if (response != null) {
                    try { response.close(); } catch (Exception ignore) {}
                }

                if (status >= 200 && status < 300) {
                    return VoucherLookupResult.FOUND;
                }
                if (status == 404) {
                    if (i < attempts - 1) {
                        // Wait briefly and try again to allow e-conomic to persist the voucher
                        try { Thread.sleep(verifyRetrySleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    continue;
                }

                // Unexpected status (throttling, 5xx, auth) -> the voucher's state is NOT determined
                log.warnf("Voucher verification unexpected status for expense %s: journal=%d, year=%s, voucher=%d, status=%d",
                        expense.getUuid(), expense.getJournalnumber(), urlYear, expense.getVouchernumber(), status);
                return VoucherLookupResult.UNKNOWN;
            }

            // Consistent 404 across all attempts -> proven absent
            return VoucherLookupResult.NOT_FOUND;
        } catch (RuntimeException e) {
            // If a provider still mapped a 404 to exception, treat as proven absence
            String msg = e.getMessage();
            if (msg != null && (msg.contains("httpStatusCode\":404") || msg.contains("HTTP 404"))) {
                return VoucherLookupResult.NOT_FOUND;
            }
            log.error("Error verifying voucher existence for expense " + expense.getUuid(), e);
            return VoucherLookupResult.UNKNOWN;
        }
    }

    /**
     * Checks whether the stored voucher is present in the booked accounting-year
     * ledger. Complements {@link #checkVoucherExists(Expense)} (draft journal) so a
     * BOOKED voucher is not mistaken for "missing". Missing triple counts as
     * {@code NOT_FOUND}; a failed lookup is {@code UNKNOWN}, never absence.
     */
    public VoucherLookupResult checkVoucherBooked(Expense expense) {
        if (expense.getVouchernumber() <= 0 || expense.getJournalnumber() == null || expense.getAccountingyear() == null) {
            return VoucherLookupResult.NOT_FOUND;
        }
        try (EconomicsAPI api = getApiForExpense(expense)) {
            return checkVoucherBooked(expense, api);
        } catch (Exception e) {
            log.warn("Booked-ledger lookup failed for expense " + expense.getUuid() + ": " + e);
            return VoucherLookupResult.UNKNOWN;
        }
    }

    /** Lookup body of {@link #checkVoucherBooked(Expense)}; package-private so tests can inject the API. */
    VoucherLookupResult checkVoucherBooked(Expense expense, EconomicsAPI api) {
        String yearId = DateUtils.toEconomicsUrlYear(expense.getAccountingyear());
        String filter = "voucherNumber$eq:" + expense.getVouchernumber();
        try {
            Response yr = api.getYearEntries(yearId, filter, 1000, 0);
            int status = yr != null ? yr.getStatus() : -1;
            String body = null;
            try { if (yr != null) body = yr.readEntity(String.class); }
            finally { if (yr != null) yr.close(); }
            if (status >= 200 && status < 300) {
                return hasAnyLedgerEntries(body) ? VoucherLookupResult.FOUND : VoucherLookupResult.NOT_FOUND;
            }
            if (status == 404) {
                // Year not addressable -> nothing can be booked under it for this expense
                return VoucherLookupResult.NOT_FOUND;
            }
            log.warn("Booked-ledger lookup unexpected status for expense " + expense.getUuid()
                    + ": year=" + yearId + ", voucher=" + expense.getVouchernumber() + ", status=" + status);
            return VoucherLookupResult.UNKNOWN;
        } catch (Exception e) {
            log.warn("Booked-ledger lookup failed for expense " + expense.getUuid() + ": " + e);
            return VoucherLookupResult.UNKNOWN;
        }
    }

    /** e-conomic list responses wrap items in a "collection" array. True when non-empty. */
    private boolean hasAnyLedgerEntries(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode coll = root.get("collection");
            if (coll != null && coll.isArray()) return coll.size() > 0;
            return root.isArray() && root.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete voucher from e-conomic using NEW Journals API.
     * <p>
     * Uses GET-then-DELETE pattern: first fetches entry details to obtain
     * entryNumber and objectVersion, then deletes the draft entry.
     * </p>
     * <p>
     * This method deletes an unbooked voucher from the e-conomic journal.
     * Vouchers that have been booked to the accounting year cannot be deleted (HTTP 409).
     * If the voucher is not found (HTTP 404), it's treated as already deleted (auto-reconcile).
     * </p>
     *
     * @param expense Expense with voucher reference to delete
     * @return Response from DELETE operation (HTTP 204 on success, HTTP 404 if not found)
     * @throws Exception if deletion fails or voucher is booked
     */
    public Response deleteVoucher(Expense expense) throws Exception {
        log.info("EconomicsService.deleteVoucher");
        log.infof("Deleting voucher for expense %s: journal=%d, voucher=%d, accountingYear=%s",
                expense.getUuid(), expense.getJournalnumber(), expense.getVouchernumber(), expense.getAccountingyear());

        // Validate voucher references exist
        if (expense.getVouchernumber() <= 0 ||
            expense.getJournalnumber() == null ||
            expense.getAccountingyear() == null) {
            log.warnf("Cannot delete voucher for expense %s: missing voucher references", expense.getUuid());
            throw new IllegalArgumentException("Expense has no voucher reference to delete");
        }

        // Get integration keys for expense company
        IntegrationKey.IntegrationKeyValue result = getIntegrationKey(expense);
        log.info("integrationKeyValue = " + result);

        try {
            // STEP 1: GET entry details (to obtain entryNumber and objectVersion)
            log.infof("Step 1: Fetching entry details for voucher %d in journal %d",
                expense.getVouchernumber(), expense.getJournalnumber());

            JournalEntryResponse.Entry entry = getEntryDetails(expense, result);

            if (entry == null) {
                // Voucher not found - treat as already deleted (auto-reconcile)
                log.warnf("Voucher not found in e-conomic (404) - may have been manually deleted: journal=%d, voucher=%d",
                        expense.getJournalnumber(), expense.getVouchernumber());
                return Response.status(404).build();
            }

            // STEP 2: DELETE draft entry using details from GET
            log.infof("Step 2: Deleting draft entry %d for voucher %d", entry.entryNumber, entry.voucherNumber);

            DraftEntryDeleteRequest deleteRequest = new DraftEntryDeleteRequest(
                expense.getJournalnumber(),
                expense.getVouchernumber(),
                entry.entryNumber,
                entry.objectVersion
            );

            try (EconomicsJournalsAPI journalsAPI = getJournalsAPI(result)) {
                Response response = journalsAPI.deleteDraftEntry(deleteRequest);

                int status = response.getStatus();
                log.infof("DELETE draft entry response: status=%d for expense %s", status, expense.getUuid());

                // Success: 204 No Content or other 2xx
                if (status == 204 || (status >= 200 && status < 300)) {
                    log.infof("Draft entry deleted successfully in e-conomic: journal=%d, voucher=%d, entry=%d",
                            expense.getJournalnumber(), expense.getVouchernumber(), entry.entryNumber);
                    return response;
                }

                // 409: Conflict - voucher may be booked
                if (status == 409) {
                    String errorBody = safeRead(response);
                    log.errorf("Cannot delete booked voucher (409): journal=%d, voucher=%d, error: %s",
                            expense.getJournalnumber(), expense.getVouchernumber(), errorBody);
                    throw new ExpenseUploadException("Cannot delete booked voucher", null, status, errorBody);
                }

                // Other errors
                String errorBody = safeRead(response);
                log.errorf("Failed to delete draft entry: status=%d, journal=%d, voucher=%d, entry=%d, error: %s",
                        status, expense.getJournalnumber(), expense.getVouchernumber(), entry.entryNumber, errorBody);
                throw new ExpenseUploadException("Failed to delete draft entry from e-conomic", null, status, errorBody);
            }

        } catch (ExpenseUploadException e) {
            // Re-throw our own exceptions
            throw e;
        } catch (Exception e) {
            // Check if it's a 404 from GET (voucher not found) - auto-reconcile
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("404")) {
                log.warnf("Voucher not found in e-conomic (404) - auto-reconciling for expense %s", expense.getUuid());
                return Response.status(404).build();
            }

            // Other errors - rethrow
            log.errorf(e, "Failed to delete voucher from e-conomic for expense %s", expense.getUuid());
            throw new RuntimeException("Failed to delete voucher from e-conomic", e);
        }
    }
}

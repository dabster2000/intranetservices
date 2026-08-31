package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.remote.EconomicsApiException;
import dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper;
import dk.trustworks.intranet.expenseservice.services.EconomicsService.VoucherRejection;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two things that were tangled together in production and must stay apart: <em>which</em> entry
 * dates a voucher is worth trying, and <em>why</em> e-conomic refused one.
 *
 * <h2>The misclassification</h2>
 * e-conomic returns {@code E04041} for two unrelated conditions and tells them apart only by the
 * {@code propertyName} of the error node — {@code "Date"} for a barred period, {@code "account"}
 * for an account that does not exist. Matching the bare code read the second as the first: on
 * 2026-08-31 expense 6258a643-fb69-4e41-894e-426cb7768ecc (Trustworks Technology ApS) burned eight
 * date-shifted POSTs and parked with an instruction to unbar a period, while TWT's FY2026/27 was
 * open on all twelve periods and the real fault was that account 3562 is not in TWT's chart of
 * accounts at all ({@code GET /accounts/3562} → 404, verified against the live agreement). Four
 * more rows carried the same wrong instruction, three of them on the employee's contra account.
 *
 * <h2>The entry date</h2>
 * The voucher used to be dated {@code LocalDate.now()}, so a cost landed in whichever period the
 * batch happened to run in rather than the one it was incurred in. The candidates now start at the
 * expense date and walk forward by whole periods, capped a week past today — because "the first
 * open period" is not always near: for Trustworks Cyber Security ApS on 2026-08-31 the earliest
 * unbarred period was 2027-06-01, ten months of future-dating that no cap-less walk would refuse.
 */
class EconomicsVoucherEntryDateTest {

    private EconomicsService serviceFor(String envId) {
        EconomicsService s = new EconomicsService();
        s.environmentId = envId;
        return s;
    }

    /**
     * Verbatim from CloudWatch, 2026-08-31T00:33:19Z, expense
     * 6258a643-fb69-4e41-894e-426cb7768ecc (Anders Thøgersen, Trustworks Technology ApS,
     * account 3562 "Course outside EU"). Note there is no {@code date} error anywhere in it —
     * the only E04041 hangs off {@code propertyName: "account"}.
     */
    private static final String PRODUCTION_400_MISSING_EXPENSE_ACCOUNT =
            "{\"message\":\"Validation failed. 2 errors found.\",\"errorCode\":\"E04300\","
                    + "\"developerHint\":\"Inspect validation errors and correct your request.\","
                    + "\"logId\":\"a337f172db207e9c-DUB\",\"httpStatusCode\":400,"
                    + "\"errors\":[{\"arrayIndex\":0,\"account\":{\"errors\":[{"
                    + "\"propertyName\":\"account\","
                    + "\"errorMessage\":\"Account(s) is not found or barred.\","
                    + "\"errorCode\":\"E04041\",\"inputValue\":\"3562\","
                    + "\"developerHint\":\"You must provide an accessible account.\"}]},"
                    + "\"entries\":{\"errors\":[],\"items\":[{\"arrayIndex\":0,"
                    + "\"account\":{\"errors\":[{\"propertyName\":\"Account\","
                    + "\"errorMessage\":\"Account '3562' not found.\",\"errorCode\":\"E07150\","
                    + "\"inputValue\":3562,\"developerHint\":\"Find a list of accounts at "
                    + "https://restapi.e-conomic.com/accounts .\"}]}}]}}],"
                    + "\"logTime\":\"2026-08-31T02:33:19\",\"errorCount\":2}";

    /**
     * Same shape, but the missing number is the employee's own contra account. Verbatim from the
     * stored error on expense d5a1d16d-6acb-41d2-b0c5-cf63904b33a6 (2026-08-23); d1839b16… and
     * 5df009a7… carry it too. Worth its own case: the remedy is a stale {@code UserAccount}
     * mapping, not a missing expense category.
     */
    private static final String PRODUCTION_400_MISSING_CONTRA_ACCOUNT =
            "{\"message\":\"Validation failed. 2 errors found.\",\"errorCode\":\"E04300\","
                    + "\"logId\":\"a2f6183c0e9c955e-DUB\",\"httpStatusCode\":400,"
                    + "\"errors\":[{\"arrayIndex\":0,\"account\":{\"errors\":[{"
                    + "\"propertyName\":\"account\","
                    + "\"errorMessage\":\"Account(s) is not found or barred.\","
                    + "\"errorCode\":\"E04041\",\"inputValue\":\"9780\"}]},"
                    + "\"entries\":{\"errors\":[],\"items\":[{\"arrayIndex\":0,"
                    + "\"contraAccount\":{\"errors\":[{\"propertyName\":\"ContraAccount\","
                    + "\"errorMessage\":\"Account '9780' not found.\",\"errorCode\":\"E07150\","
                    + "\"inputValue\":9780}]}}]}}],\"errorCount\":2}";

    /** The genuine barred-period body, 2026-08-30T00:35:42Z, expense a84274cb… (TWC). */
    private static final String PRODUCTION_400_BARRED_PERIOD =
            "{\"message\":\"Validation failed. 1 error found.\",\"errorCode\":\"E04300\","
                    + "\"httpStatusCode\":400,\"errors\":[{\"arrayIndex\":0,"
                    + "\"entries\":{\"errors\":[],\"items\":[{\"arrayIndex\":0,"
                    + "\"date\":{\"errors\":[{\"propertyName\":\"Date\","
                    + "\"errorMessage\":\"Perioden er spærret.\",\"errorCode\":\"E04041\","
                    + "\"inputValue\":{\"voucherAccountingYear\":\"2026/2027\","
                    + "\"entryDate\":\"2026-08-30\"},"
                    + "\"developerHint\":\"You cannot create an entry with a date that is barred "
                    + "in the accounting year.\"}]}}]}}],\"errorCount\":1}";

    // ---------------------------------------------------------------- classification

    @Test
    void a_missing_expense_account_is_not_a_barred_period() {
        assertEquals(VoucherRejection.ACCOUNT_NOT_FOUND,
                EconomicsService.classifyRejection(PRODUCTION_400_MISSING_EXPENSE_ACCOUNT),
                "E04041 under propertyName=account means the account is absent, not the period");
    }

    @Test
    void a_missing_contra_account_is_not_a_barred_period() {
        assertEquals(VoucherRejection.ACCOUNT_NOT_FOUND,
                EconomicsService.classifyRejection(PRODUCTION_400_MISSING_CONTRA_ACCOUNT));
    }

    @Test
    void a_barred_entry_date_is_still_a_barred_period() {
        assertEquals(VoucherRejection.BARRED_PERIOD,
                EconomicsService.classifyRejection(PRODUCTION_400_BARRED_PERIOD));
        assertTrue(serviceFor("production").isPeriodClosedError(PRODUCTION_400_BARRED_PERIOD));
    }

    /**
     * The ambiguity, isolated: the SAME code, the SAME body shape, decided only by the property it
     * blames. Getting this pair wrong in either direction is a production incident — one way strands
     * expenses a date shift would have rescued, the other way sends Accounting to the period
     * settings for a chart-of-accounts fault.
     */
    @Test
    void e04041_is_read_by_the_property_it_blames() {
        String underDate = "{\"errors\":[{\"date\":{\"errors\":[{\"propertyName\":\"Date\","
                + "\"errorCode\":\"E04041\"}]}}]}";
        String underAccount = "{\"errors\":[{\"account\":{\"errors\":[{\"propertyName\":\"account\","
                + "\"errorCode\":\"E04041\"}]}}]}";

        assertEquals(VoucherRejection.BARRED_PERIOD, EconomicsService.classifyRejection(underDate));
        assertEquals(VoucherRejection.ACCOUNT_NOT_FOUND,
                EconomicsService.classifyRejection(underAccount));

        // And with no property named at all there is no ambiguity to resolve, so the older reading
        // is kept rather than silently dropping a shape production has not shown us yet.
        assertEquals(VoucherRejection.BARRED_PERIOD,
                EconomicsService.classifyRejection("{\"errors\":[{\"errorCode\":\"E04041\"}]}"));
    }

    @Test
    void legacy_name_style_codes_without_a_property_still_match() {
        // Bodies from older endpoints carry no propertyName at all; none of their codes is
        // ambiguous, so they are matched on the signal text as before.
        for (String code : List.of("AccountingYearClosed", "EntryDateInClosedPeriod",
                "DateInClosedPeriod", "PeriodClosed", "ClosedAccountingYear", "E04870")) {
            assertEquals(VoucherRejection.BARRED_PERIOD,
                    EconomicsService.classifyRejection("{\"errorCode\":\"" + code + "\"}"), code);
        }
    }

    @Test
    void an_unrelated_400_is_classified_as_neither() {
        assertEquals(VoucherRejection.OTHER, EconomicsService.classifyRejection(null));
        assertEquals(VoucherRejection.OTHER, EconomicsService.classifyRejection(""));
        assertEquals(VoucherRejection.OTHER,
                EconomicsService.classifyRejection("{\"errorCode\":\"URLChanged\"}"));
        assertEquals(VoucherRejection.OTHER,
                EconomicsService.classifyRejection("{\"errorCode\":\"E04010\",\"errors\":[{"
                        + "\"text\":{\"errors\":[{\"propertyName\":\"Text\","
                        + "\"errorMessage\":\"Text is too long.\"}]}}]}"));
    }

    /**
     * The whole chain, as it runs in production: vendor 400 → error mapper → thrown exception →
     * the shift decision. Eight POSTs were spent here on a fault no date could clear.
     */
    @Test
    void the_missing_account_400_does_not_trigger_the_date_shift() {
        Response vendorResponse = mock(Response.class);
        when(vendorResponse.getStatus()).thenReturn(400);
        when(vendorResponse.readEntity(String.class))
                .thenReturn(PRODUCTION_400_MISSING_EXPENSE_ACCOUNT);

        EconomicsApiException mapped = assertInstanceOf(EconomicsApiException.class,
                new EconomicsErrorMapper().toThrowable(vendorResponse));

        assertFalse(serviceFor("production")
                        .shouldShiftVoucherDate(mapped.getStatus(), mapped.getBody()),
                "shifting the entry date cannot conjure an account into the chart of accounts");
    }

    // ---------------------------------------------------------------- messages

    @Test
    void account_message_names_the_account_and_the_company_without_vendor_json() {
        String msg = EconomicsService.accountNotFoundMessage(
                "Trustworks Technology ApS", 3562, 7030, List.of(3562));

        assertTrue(msg.contains("3562"), msg);
        assertTrue(msg.contains("Trustworks Technology ApS"), msg);
        assertTrue(msg.contains("chart of accounts"), "must point at the chart, not the period: " + msg);
        assertFalse(msg.contains("Regnskabsår"), "must NOT send anyone to the period settings: " + msg);
        assertFalse(msg.contains("errorCode"), msg);
        assertFalse(msg.contains("{"), msg);
    }

    @Test
    void account_message_says_when_it_is_the_employees_contra_account() {
        String msg = EconomicsService.accountNotFoundMessage(
                "Trustworks Cyber Security ApS", 2250, 9780, List.of(9780));

        assertTrue(msg.contains("9780"), msg);
        assertTrue(msg.contains("contra account"),
                "a contra-account miss is a stale employee mapping, and must say so: " + msg);
    }

    @Test
    void account_message_stays_actionable_without_a_company_or_a_proven_account() {
        String msg = EconomicsService.accountNotFoundMessage(null, 3562, 7030, List.of());

        assertTrue(msg.contains("3562"), msg);
        assertTrue(msg.contains("7030"), msg);
        assertFalse(msg.contains("null"), "no null leaking into operator text: " + msg);
    }

    // ---------------------------------------------------------------- entry dates

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);

    @Test
    void the_expense_date_is_tried_first() {
        List<LocalDate> dates =
                EconomicsService.candidateEntryDates(LocalDate.of(2026, 8, 28), TODAY);

        assertEquals(LocalDate.of(2026, 8, 28), dates.get(0),
                "the cost belongs to the period it was incurred in, so that date is tried first");
        assertTrue(dates.contains(TODAY), "today stays in the list as the fallback: " + dates);
    }

    @Test
    void a_late_submission_walks_forward_one_period_at_a_time() {
        // Expense incurred 2026-06-11, submitted in August: June is FY2025/26, July opens
        // FY2026/27. e-conomic bars whole periods, so month-firsts are the only candidates
        // that can change the answer — walking day by day asks June the same question thirty times.
        List<LocalDate> dates =
                EconomicsService.candidateEntryDates(LocalDate.of(2026, 6, 11), TODAY);

        assertEquals(List.of(
                        LocalDate.of(2026, 6, 11),
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3),
                        LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6),
                        LocalDate.of(2026, 9, 7)),
                dates);
    }

    /**
     * The case the whole guard exists for. On 2026-08-31 Trustworks Cyber Security ApS had every
     * period of FY2025/26 <em>and</em> FY2026/27 through 2027-05-31 barred — the earliest date it
     * would have accepted was 2027-06-01. The candidate list must not reach it: dating a
     * 2026-08-28 lunch into June 2027 is worse than not posting it.
     */
    @Test
    void the_walk_never_leaves_the_week_after_today_however_barred_the_year_is() {
        List<LocalDate> dates =
                EconomicsService.candidateEntryDates(LocalDate.of(2026, 8, 28), TODAY);

        LocalDate last = dates.get(dates.size() - 1);
        assertEquals(TODAY.plusDays(7), last);
        assertFalse(dates.contains(LocalDate.of(2027, 6, 1)),
                "the first open TWC period was ten months out; it is not a candidate: " + dates);
        for (LocalDate d : dates) {
            assertFalse(d.isAfter(TODAY.plusDays(7)), d + " is past the cap");
        }
    }

    @Test
    void a_very_late_submission_is_capped_rather_than_walked_forever() {
        // Production's worst real lag is 442 days. The walk must terminate and must still end
        // on the today+7 tail, whatever it had to skip to get there.
        List<LocalDate> dates =
                EconomicsService.candidateEntryDates(LocalDate.of(2020, 8, 10), TODAY);

        assertTrue(dates.size() <= 32, "unbounded walk: " + dates.size() + " candidates");
        assertEquals(LocalDate.of(2020, 8, 10), dates.get(0));
        assertEquals(TODAY.plusDays(7), dates.get(dates.size() - 1));
        assertTrue(dates.contains(TODAY), "today must survive the cap: " + dates);
    }

    @Test
    void an_expense_dated_today_or_later_behaves_exactly_as_before() {
        List<LocalDate> expected = List.of(
                TODAY, TODAY.plusDays(1), TODAY.plusDays(2), TODAY.plusDays(3),
                TODAY.plusDays(4), TODAY.plusDays(5), TODAY.plusDays(6), TODAY.plusDays(7));

        assertEquals(expected, EconomicsService.candidateEntryDates(TODAY, TODAY));
        assertEquals(expected, EconomicsService.candidateEntryDates(TODAY.plusDays(3), TODAY),
                "a future expense date is not a licence to post a future voucher");
        assertEquals(expected, EconomicsService.candidateEntryDates(null, TODAY),
                "no expense date is the pre-existing behaviour, unchanged");
    }

    @Test
    void candidates_are_chronological_and_free_of_duplicates() {
        // The first open one wins, so order IS the policy; a duplicate would also burn a POST.
        List<LocalDate> dates =
                EconomicsService.candidateEntryDates(LocalDate.of(2026, 8, 1), TODAY);

        assertEquals(dates.size(), dates.stream().distinct().count(), "duplicates: " + dates);
        for (int i = 1; i < dates.size(); i++) {
            assertTrue(dates.get(i).isAfter(dates.get(i - 1)),
                    "out of order at " + i + ": " + dates);
        }
    }
}

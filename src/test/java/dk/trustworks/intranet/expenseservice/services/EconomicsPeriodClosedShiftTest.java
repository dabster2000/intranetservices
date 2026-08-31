package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsApiException;
import dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the closed-period auto-shift contract: the detector recognises e-conomic's
 * closed-period error variants, and the shift idempotency key is distinct from both
 * the standard and orphan-retry keys so e-conomic's cache treats it as fresh.
 */
class EconomicsPeriodClosedShiftTest {

    private EconomicsService serviceFor(String envId) {
        EconomicsService s = new EconomicsService();
        s.environmentId = envId;
        return s;
    }

    @Test
    void period_closed_error_detected_on_known_errorcodes() {
        EconomicsService s = serviceFor("production");

        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"AccountingYearClosed\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"EntryDateInClosedPeriod\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"DateInClosedPeriod\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"PeriodClosed\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"ClosedAccountingYear\",\"status\":400}"));
    }

    @Test
    void period_closed_detector_ignores_other_400s() {
        EconomicsService s = serviceFor("production");

        assertFalse(s.isPeriodClosedError(null));
        assertFalse(s.isPeriodClosedError(""));
        assertFalse(s.isPeriodClosedError("{\"errorCode\":\"URLChanged\",\"status\":400}"));
        assertFalse(s.isPeriodClosedError("{\"errorCode\":\"ValidationFailed\",\"status\":400}"));
    }

    @Test
    void period_shift_key_distinct_from_standard_and_orphan_keys() {
        EconomicsService s = serviceFor("production");
        Expense e = new Expense();
        e.setUuid("abc-123");

        String standard = s.buildIdempotencyKey(e, 9);
        String shift1 = s.buildPeriodShiftIdempotencyKey(e, LocalDate.of(2026, 9, 1));
        String shift7 = s.buildPeriodShiftIdempotencyKey(e, LocalDate.of(2026, 9, 7));

        assertEquals("production-expense-abc-123-j9", standard);
        assertEquals("production-expense-abc-123-period-shift-2026-09-01", shift1);
        assertEquals("production-expense-abc-123-period-shift-2026-09-07", shift7);
    }

    @Test
    void period_shift_key_is_environment_scoped() {
        Expense e = new Expense();
        e.setUuid("abc-123");

        assertEquals("staging-expense-abc-123-period-shift-2026-09-03",
                serviceFor("staging").buildPeriodShiftIdempotencyKey(e, LocalDate.of(2026, 9, 3)));
        assertEquals("production-expense-abc-123-period-shift-2026-09-03",
                serviceFor("production").buildPeriodShiftIdempotencyKey(e, LocalDate.of(2026, 9, 3)));
    }

    /**
     * The body e-conomic returned in production on 2026-08-29T00:19:34Z for expense
     * ab4db6b7-a441-4689-be6a-57cbcdd8b237 (TWC, accounting year 2026/2027 barred).
     * Nesting and every marker are verbatim from the logged HTTP 400; the log line's
     * elided envelope fields are omitted so the literal is valid JSON.
     */
    private static final String PRODUCTION_BARRED_PERIOD_BODY = """
            {
              "errorCode": "E04300",
              "errors": [
                {
                  "entries": {
                    "items": [
                      {
                        "date": {
                          "errors": [
                            {
                              "propertyName": "Date",
                              "errorMessage": "Perioden er spærret.",
                              "errorCode": "E04041",
                              "inputValue": {
                                "voucherAccountingYear": "2026/2027",
                                "entryDate": "2026-08-29"
                              },
                              "developerHint": "You cannot create an entry with a date that is barred in the accounting year."
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """;

    @Test
    void production_barred_period_body_is_detected_as_period_closed() {
        EconomicsService s = serviceFor("production");

        assertTrue(s.isPeriodClosedError(PRODUCTION_BARRED_PERIOD_BODY),
                "the 2026-08-29 production 400 (E04041 / \"Perioden er spærret.\") must engage the auto-shift retry");
    }

    @Test
    void each_barred_period_marker_is_detected_on_its_own() {
        EconomicsService s = serviceFor("production");

        assertTrue(s.isPeriodClosedError("{\"errors\":[{\"errorCode\":\"E04041\"}]}"),
                "numeric errorCode variant, in a body that names no property — nothing there can "
                        + "mean 'account', so the pre-2026-08-31 reading stands. See "
                        + "EconomicsVoucherEntryDateTest for the ambiguity when a property IS named");
        assertTrue(s.isPeriodClosedError("{\"errors\":[{\"errorMessage\":\"Perioden er spærret.\"}]}"),
                "danish errorMessage variant");
        assertTrue(s.isPeriodClosedError("{\"errors\":[{\"developerHint\":"
                        + "\"You cannot create an entry with a date that is barred in the accounting year.\"}]}"),
                "developerHint variant");
    }

    @Test
    void period_closed_detector_reads_error_fields_not_echoed_input() {
        EconomicsService s = serviceFor("production");

        // "PeriodClosed" occurs only inside the caller's own echoed input, never as an
        // error code/message/hint — a whole-body contains() would misfire here.
        assertFalse(s.isPeriodClosedError(
                "{\"errorCode\":\"E04300\",\"errors\":[{\"text\":{\"errors\":[{"
                        + "\"errorCode\":\"E04010\",\"errorMessage\":\"Text is too long.\","
                        + "\"inputValue\":\"Lunch PeriodClosed #ab4db6b7\"}]}}]}"));
    }

    @Test
    void barred_period_message_names_the_company_and_the_accounting_year() {
        String msg = EconomicsService.barredPeriodMessage(
                "Trustworks Cyber Security ApS", "2026/2027",
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 7));

        assertTrue(msg.contains("Trustworks Cyber Security ApS"), msg);
        assertTrue(msg.contains("2026/2027"), msg);
        assertTrue(msg.contains("Indstillinger"), "must name where to unbar: " + msg);
    }

    @Test
    void barred_period_message_carries_no_vendor_json() {
        String msg = EconomicsService.barredPeriodMessage("Trustworks A/S", "2026/2027",
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 7));

        // The whole point: the expense record must stop carrying the raw 400.
        assertFalse(msg.contains("errorCode"), msg);
        assertFalse(msg.contains("developerHint"), msg);
        assertFalse(msg.contains("{"), msg);
    }

    @Test
    void barred_period_message_stays_actionable_without_a_company_name() {
        String msg = EconomicsService.barredPeriodMessage(null, "2026/2027",
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 7));

        assertTrue(msg.contains("2026/2027"), msg);
        assertFalse(msg.contains("null"), "no null leaking into operator text: " + msg);
    }

    /**
     * The HTTP 400 body e-conomic returned in production on 2026-08-30T00:35:42Z for expense
     * a84274cb-b75f-4bd3-9bd1-432717e046cd (Jacob Boeskov, TWC, accounting year 2026/2027
     * barred), copied verbatim from the CloudWatch line — nesting, empty {@code entries.errors}
     * array, echoed {@code inputValue} and all. Expense ab414c17-fce1-48d2-ad9a-c22cb588f8ed
     * failed 11 seconds later with the same shape.
     */
    private static final String PRODUCTION_400_BODY_2026_08_30 =
            "{\"message\":\"Validation failed. 1 error found.\",\"errorCode\":\"E04300\","
                    + "\"developerHint\":\"Inspect validation errors and correct your request.\","
                    + "\"logId\":\"a32fb78ed83c0ccb-DUB\",\"httpStatusCode\":400,"
                    + "\"errors\":[{\"arrayIndex\":0,\"entries\":{\"errors\":[],\"items\":[{"
                    + "\"arrayIndex\":0,\"date\":{\"errors\":[{\"propertyName\":\"Date\","
                    + "\"errorMessage\":\"Perioden er spærret.\",\"errorCode\":\"E04041\","
                    + "\"inputValue\":{\"voucherAccountingYear\":\"2026/2027\","
                    + "\"entryDate\":\"2026-08-30\"},"
                    + "\"developerHint\":\"You cannot create an entry with a date that is barred "
                    + "in the accounting year.\"}]}}]}}],"
                    + "\"logTime\":\"2026-08-30T02:35:42\",\"errorCount\":1}";

    @Test
    void the_2026_08_30_production_400_is_recognised_as_a_barred_period() {
        assertTrue(serviceFor("production").isPeriodClosedError(PRODUCTION_400_BODY_2026_08_30),
                "E04041 is nested under errors[].entries.items[].date.errors[]; the top-level "
                        + "errorCode is only E04300 \"Validation failed\"");
    }

    /**
     * The regression that actually mattered. {@code isPeriodClosedError} was already correct and
     * already live in production (commit 766c1462, image 1766df41) when these two expenses failed
     * — and they still failed on the first attempt, because {@code EconomicsErrorMapper} is
     * registered on {@code EconomicsAPI} and converts the 400 into a thrown exception, so
     * {@code postVoucher} never returns the {@code Response} whose status {@code sendVoucher}
     * was inspecting. The detector was reachable only from a branch that could not execute.
     *
     * <p>This walks the real chain — vendor response → mapper → exception → shift decision —
     * rather than handing the body to the detector directly, which is precisely the step the
     * old tests took for granted.
     */
    @Test
    void production_400_survives_the_error_mapper_and_still_triggers_the_date_shift() {
        Response vendorResponse = mock(Response.class);
        when(vendorResponse.getStatus()).thenReturn(400);
        when(vendorResponse.readEntity(String.class)).thenReturn(PRODUCTION_400_BODY_2026_08_30);

        RuntimeException thrown = new EconomicsErrorMapper().toThrowable(vendorResponse);

        EconomicsApiException mapped = assertInstanceOf(EconomicsApiException.class, thrown,
                "the mapper must hand the caller a status and a body, not just a message");
        assertEquals(400, mapped.getStatus());
        assertEquals(PRODUCTION_400_BODY_2026_08_30, mapped.getBody());

        assertTrue(serviceFor("production").shouldShiftVoucherDate(mapped.getStatus(), mapped.getBody()),
                "the barred-period 400 must engage the auto-shift retry after passing through "
                        + "the rest-client error mapper");
    }

    @Test
    void non_period_400s_and_non_400_statuses_do_not_trigger_the_date_shift() {
        EconomicsService s = serviceFor("production");

        assertFalse(s.shouldShiftVoucherDate(400, "{\"errorCode\":\"URLChanged\"}"),
                "an idempotency-key collision is not a barred period");
        assertFalse(s.shouldShiftVoucherDate(500, PRODUCTION_400_BODY_2026_08_30),
                "only a 400 means the vendor rejected the date; a 5xx is a transport failure");
        assertFalse(s.shouldShiftVoucherDate(429, PRODUCTION_400_BODY_2026_08_30),
                "throttling must not be mistaken for a barred period");
        assertFalse(s.shouldShiftVoucherDate(400, null));
    }
}

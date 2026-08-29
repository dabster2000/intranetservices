package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        String shift1 = s.buildPeriodShiftIdempotencyKey(e, 1);
        String shift7 = s.buildPeriodShiftIdempotencyKey(e, 7);

        assertEquals("production-expense-abc-123-j9", standard);
        assertEquals("production-expense-abc-123-period-shift-1", shift1);
        assertEquals("production-expense-abc-123-period-shift-7", shift7);
    }

    @Test
    void period_shift_key_is_environment_scoped() {
        Expense e = new Expense();
        e.setUuid("abc-123");

        assertEquals("staging-expense-abc-123-period-shift-3",
                serviceFor("staging").buildPeriodShiftIdempotencyKey(e, 3));
        assertEquals("production-expense-abc-123-period-shift-3",
                serviceFor("production").buildPeriodShiftIdempotencyKey(e, 3));
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
                "numeric errorCode variant");
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
                "Trustworks Cyber Security ApS", "2026/2027", 7);

        assertTrue(msg.contains("Trustworks Cyber Security ApS"), msg);
        assertTrue(msg.contains("2026/2027"), msg);
        assertTrue(msg.contains("Indstillinger"), "must name where to unbar: " + msg);
    }

    @Test
    void barred_period_message_carries_no_vendor_json() {
        String msg = EconomicsService.barredPeriodMessage("Trustworks A/S", "2026/2027", 7);

        // The whole point: the expense record must stop carrying the raw 400.
        assertFalse(msg.contains("errorCode"), msg);
        assertFalse(msg.contains("developerHint"), msg);
        assertFalse(msg.contains("{"), msg);
    }

    @Test
    void barred_period_message_stays_actionable_without_a_company_name() {
        String msg = EconomicsService.barredPeriodMessage(null, "2026/2027", 7);

        assertTrue(msg.contains("2026/2027"), msg);
        assertFalse(msg.contains("null"), "no null leaking into operator text: " + msg);
    }
}

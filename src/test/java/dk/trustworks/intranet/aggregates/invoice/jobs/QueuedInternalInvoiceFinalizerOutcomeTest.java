package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.jobs.QueuedInternalInvoiceFinalizer.Outcome;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the nightly finalizer reports after {@code finalizeAutomatically} returns.
 *
 * <p>That call swallows a DEBTOR-side voucher failure on purpose (the issuer booking is
 * irreversible, so throwing would roll back the local record of a real booking), which makes its
 * return value the only signal. On 2026-09-09 nobody read it: invoice 7803f536 came back
 * PARTIALLY_UPLOADED — booked as 70479 at the issuer, refused at the debtor — and the job logged
 * "Successfully auto-finalized". These tests pin the replacement contract: a half-booked invoice
 * is a distinct {@link Outcome}, reaches ERROR exactly once carrying the
 * {@code INTERNAL_INVOICE_HALF_BOOKED} token and the identifiers an operator needs, and never
 * produces the success line.
 */
class QueuedInternalInvoiceFinalizerOutcomeTest {

    private static final String INV = "7803f536-ecc1-4bcf-8f47-b8574795155b";
    private static final String TWT = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";
    private static final String TW_AS = "d8894494-2fb4-4f72-9e05-e6032e6dd691";

    private final QueuedInternalInvoiceFinalizer finalizer = new QueuedInternalInvoiceFinalizer();

    private Logger logger;
    private RecordingHandler handler;
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        // Surefire installs org.jboss.logmanager.LogManager (pom.xml), so the @JBossLog logger is a
        // java.util.logging.Logger and can be observed through the JUL API.
        logger = Logger.getLogger(QueuedInternalInvoiceFinalizer.class.getName());
        originalLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler = new RecordingHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void releaseLog() {
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
    }

    @Test
    void a_partially_uploaded_result_is_HALF_BOOKED_and_reaches_ERROR_once_with_the_token() {
        Invoice inv = finalized(EconomicsInvoiceStatus.PARTIALLY_UPLOADED);

        Outcome outcome = finalizer.outcomeOf(inv, "queued invoice");

        assertEquals(Outcome.HALF_BOOKED, outcome);
        List<String> errors = renderedAt(Level.SEVERE);
        assertEquals(1, errors.size(), "exactly one ERROR per half-booked invoice, got: " + errors);
        String error = errors.get(0);
        assertTrue(error.contains(QueuedInternalInvoiceFinalizer.HALF_BOOKED_TOKEN), error);
        assertTrue(error.contains(INV), "must name the invoice: " + error);
        assertTrue(error.contains("70479"), "must name the booked number the reconcile needs: " + error);
        assertTrue(error.contains(TW_AS), "must name the debtor whose journal to check: " + error);
        assertTrue(error.contains("reconcile-booking"), "must point at the completion path: " + error);
        assertFalse(allRendered().stream().anyMatch(m -> m.contains("Successfully")),
                "a half-booked invoice must never be reported as a success: " + allRendered());
    }

    @Test
    void a_fully_booked_result_is_PROCESSED_and_logs_success_at_INFO_only() {
        Invoice inv = finalized(EconomicsInvoiceStatus.BOOKED);

        Outcome outcome = finalizer.outcomeOf(inv, "queued invoice");

        assertEquals(Outcome.PROCESSED, outcome);
        assertEquals(List.of(), renderedAtLeast(Level.WARNING),
                "a clean two-sided booking must not emit WARN or ERROR");
        List<String> infos = renderedAt(Level.INFO);
        assertTrue(infos.stream().anyMatch(m -> m.contains("Successfully auto-finalized") && m.contains(INV)),
                infos.toString());
        assertFalse(allRendered().stream()
                        .anyMatch(m -> m.contains(QueuedInternalInvoiceFinalizer.HALF_BOOKED_TOKEN)),
                "the alarm token must be absent from a healthy run");
    }

    @Test
    void the_settlement_pass_uses_the_same_decision() {
        assertEquals(Outcome.HALF_BOOKED,
                finalizer.outcomeOf(finalized(EconomicsInvoiceStatus.PARTIALLY_UPLOADED), "settlement internal"));
        assertEquals(Outcome.PROCESSED,
                finalizer.outcomeOf(finalized(EconomicsInvoiceStatus.BOOKED), "settlement internal"));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** 7803f536 as finalizeAutomatically returned it: CREATED, booked 70479, status as given. */
    private static Invoice finalized(EconomicsInvoiceStatus economicsStatus) {
        Company issuer = new Company();
        issuer.setUuid(TWT);
        issuer.setName("Trustworks Technology ApS");
        Invoice inv = new Invoice();
        inv.setUuid(INV);
        inv.setType(InvoiceType.INTERNAL);
        inv.setStatus(InvoiceStatus.CREATED);
        inv.setCompany(issuer);
        inv.setDebtorCompanyuuid(TW_AS);
        inv.setEconomicsBookedNumber(70479);
        inv.setInvoicenumber(70479);
        inv.setEconomicsStatus(economicsStatus);
        return inv;
    }

    // ── log capture ──────────────────────────────────────────────────────────────────────────

    /** JBoss-logging *f methods deliver the format string and parameters unformatted. */
    private static String render(LogRecord record) {
        String message = record.getMessage() == null ? "" : record.getMessage();
        Object[] params = record.getParameters();
        return params == null ? message : message + " " + Arrays.toString(params);
    }

    private List<String> allRendered() {
        return handler.snapshot().stream().map(QueuedInternalInvoiceFinalizerOutcomeTest::render).toList();
    }

    private List<String> renderedAt(Level level) {
        return handler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() == level.intValue())
                .map(QueuedInternalInvoiceFinalizerOutcomeTest::render)
                .toList();
    }

    private List<String> renderedAtLeast(Level level) {
        return handler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() >= level.intValue())
                .map(QueuedInternalInvoiceFinalizerOutcomeTest::render)
                .toList();
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }

        List<LogRecord> snapshot() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }
}

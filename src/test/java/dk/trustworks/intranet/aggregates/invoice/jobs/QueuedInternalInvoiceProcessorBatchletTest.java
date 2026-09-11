package dk.trustworks.intranet.aggregates.invoice.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueuedInternalInvoiceProcessorBatchletTest {

    @Mock QueuedInternalInvoiceFinalizer finalizer;
    @InjectMocks QueuedInternalInvoiceProcessorBatchlet batchlet;

    @Test
    void one_invoice_failure_does_not_stop_the_loop() throws Exception {
        when(finalizer.findFirstPassUuids()).thenReturn(List.of("A", "B"));
        when(finalizer.findSettlementUuids()).thenReturn(List.of());
        when(finalizer.processOne("A")).thenThrow(new RuntimeException("boom"));
        when(finalizer.processOne("B")).thenReturn(QueuedInternalInvoiceFinalizer.Outcome.PROCESSED);

        batchlet.process();

        verify(finalizer).processOne("A");
        verify(finalizer).processOne("B"); // loop continued past A's failure — proves isolation contract
    }

    /**
     * A HALF_BOOKED invoice (issuer booked, debtor voucher refused) must not be folded into
     * "processed": that is how 7803f536 hid inside a green run on 2026-09-09. It gets its own
     * column in the summary and the loop carries on.
     */
    @Test
    void a_half_booked_invoice_is_counted_separately_not_as_processed() throws Exception {
        when(finalizer.findFirstPassUuids()).thenReturn(List.of("A", "B", "C"));
        when(finalizer.findSettlementUuids()).thenReturn(List.of());
        when(finalizer.processOne("A")).thenReturn(QueuedInternalInvoiceFinalizer.Outcome.HALF_BOOKED);
        when(finalizer.processOne("B")).thenReturn(QueuedInternalInvoiceFinalizer.Outcome.PROCESSED);
        when(finalizer.processOne("C")).thenReturn(QueuedInternalInvoiceFinalizer.Outcome.SKIPPED);

        batchlet.process();

        verify(finalizer).processOne("C");   // the half-booking did not stop the run
        String summary = captured.stream()
                .filter(m -> m.contains("QueuedInternalInvoiceProcessorBatchlet completed"))
                .findFirst().orElseThrow(() -> new AssertionError("no summary line in " + captured));
        assertTrue(summary.contains("halfBooked="), summary);
        // total=3, processed=1, skipped=1, halfBooked=1, failed=0 — rendered either formatted or as
        // the raw pattern plus its parameter array, depending on the log manager in the JVM.
        assertTrue(summary.contains("[3, 1, 1, 1, 0]")
                        || summary.contains("processed=1, skipped=1, halfBooked=1, failed=0"),
                summary);
    }

    // ── log capture ──────────────────────────────────────────────────────────────────────────

    private final List<String> captured = new ArrayList<>();
    private Logger logger;
    private Handler handler;
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        logger = Logger.getLogger(QueuedInternalInvoiceProcessorBatchlet.class.getName());
        originalLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler = new Handler() {
            @Override public void publish(LogRecord r) {
                String m = r.getMessage() == null ? "" : r.getMessage();
                captured.add(r.getParameters() == null ? m : m + " " + Arrays.toString(r.getParameters()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void releaseLog() {
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
    }
}

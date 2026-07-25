package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only second consumer on the {@code expense.validate} address
 * ({@code publish} delivers to every registered handler). At the moment of
 * delivery it snapshots what a consumer running in its own transaction can
 * actually see in the database — the same view the real
 * {@link ExpenseCreatedConsumer} gets. Tests use the snapshot to prove the
 * event was published only after the producing transaction committed.
 */
@ApplicationScoped
public class ExpenseValidateEventRecorder {

    public record Receipt(boolean rowVisible, String state) {}

    private final Map<String, Receipt> receipts = new ConcurrentHashMap<>();

    @ConsumeEvent(value = "expense.validate", blocking = true)
    void record(String expenseUuid) {
        Expense e = QuarkusTransaction.requiringNew()
                .call(() -> Expense.findById(expenseUuid));
        receipts.put(expenseUuid, new Receipt(e != null, e != null ? e.getState() : null));
    }

    /** Snapshot for the uuid, or null if the event has not been delivered yet. */
    public Receipt receipt(String expenseUuid) {
        return receipts.get(expenseUuid);
    }

    public Receipt awaitReceipt(String expenseUuid, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Receipt r = receipts.get(expenseUuid);
            if (r != null) return r;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}

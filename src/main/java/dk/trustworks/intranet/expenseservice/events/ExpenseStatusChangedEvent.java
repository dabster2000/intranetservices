package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.expenseservice.model.Expense;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.EXPENSE_STATUS_CHANGED;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class ExpenseStatusChangedEvent extends AggregateRootChangeEvent {

    public ExpenseStatusChangedEvent(String aggregateRootUUID, Payload payload) {
        super(aggregateRootUUID, EXPENSE_STATUS_CHANGED, JsonObject.mapFrom(payload).encode());
    }

    @Data
    @NoArgsConstructor
    public static class Payload {
        private String expenseId;
        private String previousStatus;
        private String newStatus;
        private String reason;      // optional human reason
        private String error;       // optional error text

        // Context for listeners
        private String useruuid;
        private Integer voucherNumber;
        private Integer journalNumber;
        private String accountingYear;
        private String account;
        private Double amount;
        private String accountName;

        public static Payload from(Expense e, String previousStatus, String newStatus, String reason, Throwable t) {
            Payload p = new Payload();
            p.setExpenseId(e.getUuid());
            p.setPreviousStatus(previousStatus);
            p.setNewStatus(newStatus);
            p.setReason(reason);
            p.setError(t != null ? t.getClass().getSimpleName() + ": " + t.getMessage() : null);
            p.setUseruuid(e.getUseruuid());
            p.setVoucherNumber(e.getVouchernumber());
            p.setJournalNumber(e.getJournalnumber());
            p.setAccountingYear(e.getAccountingyear());
            p.setAccount(e.getAccount());
            p.setAmount(e.getAmount());
            p.setAccountName(e.getAccountname());
            return p;
        }
    }
}

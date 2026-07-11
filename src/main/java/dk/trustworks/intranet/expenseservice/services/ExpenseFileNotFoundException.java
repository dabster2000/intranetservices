package dk.trustworks.intranet.expenseservice.services;

public class ExpenseFileNotFoundException extends RuntimeException {

    public ExpenseFileNotFoundException(String expenseUuid, Throwable cause) {
        super("Expense file not found: " + expenseUuid, cause);
    }
}

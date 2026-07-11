package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileNotFoundException;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseCreatedConsumerFileFailureTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    @Mock
    ExpenseFileService expenseFileService;

    ExpenseCreatedConsumer consumer;
    Expense expense;

    @BeforeEach
    void setUp() {
        consumer = new ExpenseCreatedConsumer();
        consumer.expenseFileService = expenseFileService;

        expense = new Expense();
        expense.setUuid(UUID);
    }

    @Test
    void validateExpenseClassifiesMissingReceiptWithoutRetryablePrefix() {
        when(expenseFileService.getFileById(UUID))
                .thenThrow(new ExpenseFileNotFoundException(UUID, new RuntimeException("missing")));

        var result = consumer.validateExpense(expense);

        assertFalse(result.approved());
        assertEquals(ExpenseCreatedConsumer.MISSING_RECEIPT_REASON, result.reason());
    }

    @Test
    void validateExpenseDoesNotExposeAwsErrorDetails() {
        AccessDeniedException denied = AccessDeniedException.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDenied")
                        .errorMessage("arn:aws:sts::123456789012:assumed-role/example cannot s3:GetObject")
                        .build())
                .build();
        when(expenseFileService.getFileById(UUID)).thenThrow(denied);

        var result = consumer.validateExpense(expense);

        assertFalse(result.approved());
        assertEquals(ExpenseCreatedConsumer.TRANSIENT_VALIDATION_ERROR, result.reason());
    }
}

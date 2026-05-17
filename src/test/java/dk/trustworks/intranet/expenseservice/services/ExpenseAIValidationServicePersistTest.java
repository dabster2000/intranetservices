package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link ExpenseAIValidationService#persistExtractedFacts} writes
 * AI-extracted receipt facts to the {@code expenses} table and that the
 * {@code extracted_per_person_dkk} STORED generated column is recomputed by the DB.
 *
 * <p>Uses {@code @TestTransaction} so every test rolls back automatically —
 * no cleanup needed and no interference between test runs.
 */
@QuarkusTest
class ExpenseAIValidationServicePersistTest {

    @Inject
    ExpenseAIValidationService service;

    @Test
    @TestTransaction
    void persists_extracted_facts_on_expense_after_validation() {
        // Arrange: persist a minimal expense
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("test-user");
        e.setAmount(156.0);
        e.setAccount("1");
        e.setExpensedate(LocalDate.now());
        e.setDatecreated(LocalDate.now());
        e.setStatus("CREATED");
        e.persist();

        // Act: call the new method
        service.persistExtractedFacts(e.getUuid(), 156.0, 4, "Café Det Sker");

        // Assert: reload from DB and verify all 4 columns
        Expense fresh = Expense.findById(e.getUuid());
        assertNotNull(fresh, "Expense must be findable by uuid");
        assertEquals(156.0, fresh.getExtractedAmountDkk(), 0.001,
                "extractedAmountDkk must equal the persisted value");
        assertNotNull(fresh.getExtractedGuestCount(), "extractedGuestCount must not be null");
        assertEquals(4, fresh.getExtractedGuestCount().intValue(),
                "extractedGuestCount must equal the persisted value");
        assertEquals("Café Det Sker", fresh.getExtractedMerchantName(),
                "extractedMerchantName must equal the persisted value");
        // extracted_per_person_dkk = STORED generated: 156 / 4 = 39.00
        assertNotNull(fresh.getExtractedPerPersonDkk(), "extractedPerPersonDkk must be computed by the DB");
        assertEquals(39.00, fresh.getExtractedPerPersonDkk(), 0.001,
                "extractedPerPersonDkk must equal 156.00 / 4 = 39.00");
    }

    @Test
    @TestTransaction
    void persists_null_fields_when_guest_count_not_available() {
        // Represents the current state: guestCount is null (not yet in AI schema)
        Expense e = new Expense();
        e.setUuid(UUID.randomUUID().toString());
        e.setUseruuid("test-user");
        e.setAmount(200.0);
        e.setAccount("1");
        e.setExpensedate(LocalDate.now());
        e.setDatecreated(LocalDate.now());
        e.setStatus("CREATED");
        e.persist();

        service.persistExtractedFacts(e.getUuid(), 200.0, null, "Test Merchant");

        Expense fresh = Expense.findById(e.getUuid());
        assertNotNull(fresh);
        assertEquals(200.0, fresh.getExtractedAmountDkk(), 0.001);
        assertNull(fresh.getExtractedGuestCount(), "null guestCount must persist as NULL");
        assertEquals("Test Merchant", fresh.getExtractedMerchantName());
        // extracted_per_person_dkk = NULL when guest_count is NULL (DB generated column guard)
        assertNull(fresh.getExtractedPerPersonDkk(),
                "extractedPerPersonDkk must be NULL when guestCount is NULL");
    }

    @Test
    @TestTransaction
    void is_no_op_when_expense_not_found() {
        // Must not throw — graceful degradation for missing expenses
        service.persistExtractedFacts("non-existent-uuid", 100.0, 2, "Ghost Café");
        // No exception = pass
    }
}

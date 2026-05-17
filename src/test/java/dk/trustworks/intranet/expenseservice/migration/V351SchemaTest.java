package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema regression test for V351 — adds extracted-receipt-facts columns
 * to the expenses table for the Impact Preview feature in the AI Validation
 * Console.
 *
 * <p>Note: @QuarkusTest startup requires a {@code cvtool.username} config
 * entry that is not present in local dev environments. The compile gate is
 * the meaningful local check. These tests run fully in CI/deploy environments
 * where the required configuration is present.
 */
@QuarkusTest
class V351SchemaTest {

    @Inject EntityManager em;

    @Test
    @Transactional
    void extracted_columns_exist_on_expenses() {
        @SuppressWarnings("unchecked")
        List<Object> cols = em.createNativeQuery(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'expenses' AND column_name LIKE 'extracted_%'"
        ).getResultList();

        List<String> names = cols.stream().map(Object::toString).toList();
        assertTrue(names.contains("extracted_amount_dkk"),       "extracted_amount_dkk missing");
        assertTrue(names.contains("extracted_guest_count"),       "extracted_guest_count missing");
        assertTrue(names.contains("extracted_per_person_dkk"),    "extracted_per_person_dkk missing");
        assertTrue(names.contains("extracted_merchant_name"),     "extracted_merchant_name missing");
    }

    @Test
    @Transactional
    void extracted_per_person_index_exists() {
        @SuppressWarnings("unchecked")
        List<Object> idx = em.createNativeQuery(
            "SELECT index_name FROM information_schema.statistics " +
            "WHERE table_name = 'expenses' AND index_name = 'idx_expenses_extracted_per_person'"
        ).getResultList();
        assertTrue(!idx.isEmpty(), "idx_expenses_extracted_per_person missing");
    }
}

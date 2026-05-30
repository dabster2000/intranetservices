package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema + seed test for V358. @QuarkusTest needs cvtool.username → runs in CI; the local
 * gate is test-compile.
 */
@QuarkusTest
class V358SchemaTest {

    @Inject EntityManager em;

    @Test
    @Transactional
    void outcomeMode_and_threshold_columns_exist() {
        @SuppressWarnings("unchecked")
        List<Object> cols = em.createNativeQuery(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_rule_catalog'")
            .getResultList();
        List<String> names = cols.stream().map(Object::toString).toList();
        assertTrue(names.contains("outcome_mode"), "outcome_mode missing");
        assertTrue(names.contains("confidence_threshold"), "confidence_threshold missing");
    }

    @Test
    @Transactional
    void receiptReadable_isSoftFlag() {
        Object mode = em.createNativeQuery(
            "SELECT outcome_mode FROM ai_rule_catalog WHERE rule_id = 'R_RECEIPT_READABLE'")
            .getSingleResult();
        assertEquals("SOFT_FLAG", mode.toString());
    }

    @Test
    @Transactional
    void amountMismatchParams_seeded() {
        Number n = (Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM ai_validation_parameter " +
            "WHERE parameter_key IN ('amount_mismatch_soft_pct','amount_mismatch_block_pct')")
            .getSingleResult();
        assertEquals(2, n.intValue());
    }
}

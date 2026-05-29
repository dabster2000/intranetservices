package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema test for V357 — adds the unified {@code state} column + attributes to {@code expenses}.
 *
 * <p>Note: @QuarkusTest startup requires a {@code cvtool.username} config entry that is
 * not present in local dev. The compile gate is the meaningful local check; these run
 * fully in CI/deploy environments.
 */
@QuarkusTest
class V357SchemaTest {

    @Inject EntityManager em;

    @Test
    @Transactional
    void unified_state_columns_exist() {
        @SuppressWarnings("unchecked")
        List<Object> cols = em.createNativeQuery(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = DATABASE() AND table_name = 'expenses'"
        ).getResultList();
        List<String> names = cols.stream().map(Object::toString).toList();
        assertTrue(names.contains("state"),           "state missing");
        assertTrue(names.contains("attention_owner"), "attention_owner missing");
        assertTrue(names.contains("attention_kind"),  "attention_kind missing");
        assertTrue(names.contains("ai_outcome"),      "ai_outcome missing");
        assertTrue(names.contains("ai_confidence"),   "ai_confidence missing");
        assertTrue(names.contains("soft_flags"),      "soft_flags missing");
    }

    @Test
    @Transactional
    void state_index_exists() {
        @SuppressWarnings("unchecked")
        List<Object> idx = em.createNativeQuery(
            "SELECT index_name FROM information_schema.statistics " +
            "WHERE table_schema = DATABASE() AND table_name = 'expenses' AND index_name = 'idx_expenses_state'"
        ).getResultList();
        assertTrue(!idx.isEmpty(), "idx_expenses_state missing");
    }
}

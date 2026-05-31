package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Schema test for V359 — drops the legacy review_state/hr_decision* columns from {@code expenses}
 * (Phase 3 Release B). @QuarkusTest startup needs a {@code cvtool.username} config entry not present
 * in local dev, so this runs fully in CI/deploy; the local gate is test-compile.
 */
@QuarkusTest
class V359SchemaTest {

    @Inject EntityManager em;

    @Test
    @Transactional
    void legacy_review_columns_dropped() {
        @SuppressWarnings("unchecked")
        List<Object> cols = em.createNativeQuery(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = DATABASE() AND table_name = 'expenses'")
            .getResultList();
        List<String> names = cols.stream().map(Object::toString).toList();
        for (String dropped : List.of("review_state", "hr_decision", "hr_decision_by", "hr_decision_at", "hr_comment")) {
            assertFalse(names.contains(dropped), dropped + " should have been dropped");
        }
    }
}

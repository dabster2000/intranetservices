package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema test for V352 — adds the merchant_allow_list table used by the
 * AI Validation Console's "Add to allow-list" quick action.
 *
 * <p>Note: @QuarkusTest startup requires a {@code cvtool.username} config
 * entry that is not present in local dev environments. The compile gate is
 * the meaningful local check. These tests run fully in CI/deploy environments
 * where the required configuration is present.
 */
@QuarkusTest
class V352SchemaTest {

    @Inject EntityManager em;

    @Test
    @Transactional
    void merchant_allow_list_table_exists() {
        @SuppressWarnings("unchecked")
        List<Object> cols = em.createNativeQuery(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'merchant_allow_list'"
        ).getResultList();
        List<String> names = cols.stream().map(Object::toString).toList();
        assertTrue(names.contains("uuid"),                   "uuid missing");
        assertTrue(names.contains("rule_id"),                "rule_id missing");
        assertTrue(names.contains("merchant_name_pattern"),  "merchant_name_pattern missing");
        assertTrue(names.contains("match_kind"),             "match_kind missing");
        assertTrue(names.contains("notes"),                  "notes missing");
        assertTrue(names.contains("added_by_uuid"),          "added_by_uuid missing");
        assertTrue(names.contains("created_at"),             "created_at missing");
    }

    @Test
    @Transactional
    void merchant_allow_list_indexes_exist() {
        @SuppressWarnings("unchecked")
        List<Object> idx = em.createNativeQuery(
            "SELECT index_name FROM information_schema.statistics " +
            "WHERE table_name = 'merchant_allow_list' AND index_name IN ('idx_mal_rule', 'idx_mal_pattern')"
        ).getResultList();
        // Note: an index with multiple columns produces multiple rows (one per column);
        // we just need both index names to appear at least once
        List<String> names = idx.stream().map(Object::toString).toList();
        assertTrue(names.contains("idx_mal_rule"),    "idx_mal_rule missing");
        assertTrue(names.contains("idx_mal_pattern"), "idx_mal_pattern missing");
    }
}

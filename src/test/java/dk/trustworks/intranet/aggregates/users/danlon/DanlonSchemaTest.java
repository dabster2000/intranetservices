package dk.trustworks.intranet.aggregates.users.danlon;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies V369–V371 applied at @QuarkusTest startup (Flyway migrate-at-start). */
@QuarkusTest
class DanlonSchemaTest {

    @Inject EntityManager em;

    @SuppressWarnings("unchecked")
    private List<String> columns(String table) {
        return em.createNativeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = ?1")
                .setParameter(1, table)
                .getResultList().stream().map(Object::toString).toList();
    }

    @Test
    @Transactional
    void proposalTableExistsWithKeyColumnsAndUniqueSlot() {
        List<String> cols = columns("danlon_assignment_proposal");
        assertTrue(cols.contains("uuid"), "uuid missing");
        assertTrue(cols.contains("intent"), "intent missing");
        assertTrue(cols.contains("event_type"), "event_type missing");
        assertTrue(cols.contains("open_slot_key"), "open_slot_key missing");

        Number unique = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = 'danlon_assignment_proposal' " +
                "AND index_name = 'uq_danlon_proposal_open_slot'")
                .getSingleResult();
        assertTrue(unique.intValue() > 0, "uq_danlon_proposal_open_slot missing");
    }

    @Test
    @Transactional
    void sequenceTableSeededAtLeast1035() {
        Number nextValue = (Number) em.createNativeQuery(
                "SELECT next_value FROM danlon_number_sequence WHERE name = 'danlon'")
                .getSingleResult();
        assertNotNull(nextValue, "danlon sequence row missing");
        assertTrue(nextValue.longValue() >= 1035L,
                "seed must be >= 1035, was " + nextValue);
    }

    @Test
    @Transactional
    void historyHasLifecycleColumns() {
        List<String> cols = columns("user_danlon_history");
        assertTrue(cols.contains("company_uuid"), "company_uuid missing");
        assertTrue(cols.contains("closed_date"), "closed_date missing");
        assertTrue(cols.contains("closed_reason"), "closed_reason missing");
        assertTrue(cols.contains("event_type"), "event_type missing");
    }

    @Test
    @Transactional
    void eventTypeBackfillIsConsistentWithMarkers() {
        // No system-* marker row may be left with a NULL/blank event_type.
        Number mismatched = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM user_danlon_history " +
                "WHERE created_by IN ('system-first-employment','system-re-employment'," +
                "'system-company-transition','system-salary-type-change') " +
                "AND (event_type IS NULL OR event_type = '')")
                .getSingleResult();
        assertEquals(0, mismatched.intValue(), "system-* rows must have event_type backfilled");
    }
}

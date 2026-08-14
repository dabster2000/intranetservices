package dk.trustworks.intranet.competenceservice.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.count;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.expectRefused;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.row;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.rows;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.sqlStateOf;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code uk_competence_content_active} and {@code uk_competence_content_draft} over the
 * trigger-written {@code active_key} / {@code draft_key} columns (spec §4.2, §19.1, §12.2).
 *
 * <p><strong>Why this cannot be a unit test.</strong> "At most one ACTIVE and at most one
 * DRAFT per (requirement, kind)" is enforced by two MariaDB unique indexes over two columns
 * that no Java code writes: {@code trg_competence_content_version_keys_ins/upd} computes them
 * on every insert and update, and the entity does not map them at all. There is no Java-side
 * behaviour to assert. Either the index rejects the second row or the guarantee does not
 * exist.
 *
 * <p><strong>Why the three-status split matters.</strong> The key is deliberately NULL for
 * ARCHIVED, and MariaDB unique indexes ignore NULLs — that is what allows an unbounded version
 * history to sit in the same table as the two singleton slots. A migration that "tidied" the
 * key into a NOT NULL column with a sentinel value would keep the two collision tests green
 * and break the history outright, which is why {@link #manyArchivedRowsCoexist()} is here.
 *
 * <p><strong>Why the trigger is tested separately from the index.</strong> §19.1 records that
 * the spec's original {@code GENERATED ALWAYS AS … PERSISTENT} form is rejected by MariaDB
 * 10.11 (error 1901) for any string-concatenating expression, so these are plain columns a
 * trigger overwrites. Plain columns are writable, which is a way to be wrong that generated
 * columns did not have — hence {@link #triggerOverwritesAnyApplicationSuppliedKey()}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompetenceUniqueActiveConstraintTest {

    /** MariaDB integrity-constraint class; error 1062 is specifically a duplicate key. */
    private static final String INTEGRITY_CONSTRAINT = "23000";
    private static final int DUPLICATE_ENTRY = 1062;

    @Inject
    DataSource ds;

    /** One requirement per test, so a collision can only come from the row under test. */
    private final List<String> requirementUuids = new ArrayList<>();

    @AfterAll
    void dropFixtures() {
        // Nothing here is referenced by an append-only table, so all of it is removable.
        requirementUuids.forEach(uuid -> CompetenceFixtures.quietlyDropFixture(ds, uuid));
    }

    @Test
    @DisplayName("The insert trigger computes active_key / draft_key and ignores what the caller supplied")
    void triggerOverwritesAnyApplicationSuppliedKey() throws SQLException {
        String requirementUuid = requirement("trigger-writes");

        String activeUuid = CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE",
                "v1", "ACTIVE", CompetenceFixtures.MINIMAL_COURSE_PAYLOAD,
                "supplied-nonsense", "supplied-nonsense");
        String draftUuid = CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE",
                "v2", "DRAFT", CompetenceFixtures.MINIMAL_COURSE_PAYLOAD,
                "supplied-nonsense", "supplied-nonsense");

        Map<String, Object> active = version(activeUuid);
        assertEquals(requirementUuid + ":COURSE", active.get("active_key"));
        assertNull(active.get("draft_key"), "An ACTIVE row holds only the active slot");

        Map<String, Object> draft = version(draftUuid);
        assertEquals(requirementUuid + ":COURSE", draft.get("draft_key"));
        assertNull(draft.get("active_key"));
    }

    @Test
    @DisplayName("Two ACTIVE rows for the same (requirement, kind) collide")
    void twoActiveRowsCollide() throws SQLException {
        String requirementUuid = requirement("two-active");
        CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v1", "ACTIVE");

        SQLException refusal = expectRefused(ds, """
                        INSERT INTO competence_content_version
                            (uuid, requirement_uuid, content_kind, version_label, status,
                             payload_json, forced_retake)
                        VALUES (?, ?, 'COURSE', 'v2', 'ACTIVE', ?, 1)""",
                CompetenceFixtures.newUuid(), requirementUuid, CompetenceFixtures.MINIMAL_COURSE_PAYLOAD);

        assertDuplicateKey(refusal, "uk_competence_content_active");
        assertEquals(1L, activeCount(requirementUuid, "COURSE"),
                "An employee must never be able to see two live versions of the same artefact");
    }

    @Test
    @DisplayName("Two DRAFT rows for the same (requirement, kind) collide")
    void twoDraftRowsCollide() throws SQLException {
        String requirementUuid = requirement("two-draft");
        CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v1", "DRAFT");

        // The editor's save button means "this is the draft now", which is why the service
        // upserts rather than creating a second one — a second draft is not a state the
        // product has a name for, and the index says so.
        SQLException refusal = expectRefused(ds, """
                        INSERT INTO competence_content_version
                            (uuid, requirement_uuid, content_kind, version_label, status,
                             payload_json, forced_retake)
                        VALUES (?, ?, 'COURSE', 'v2', 'DRAFT', ?, 1)""",
                CompetenceFixtures.newUuid(), requirementUuid, CompetenceFixtures.MINIMAL_COURSE_PAYLOAD);

        assertDuplicateKey(refusal, "uk_competence_content_draft");
        assertEquals(1L, statusCount(requirementUuid, "COURSE", "DRAFT"));
    }

    @Test
    @DisplayName("The key carries the kind: an ACTIVE COURSE and an ACTIVE TEST coexist")
    void activeCourseAndActiveTestCoexist() throws SQLException {
        String requirementUuid = requirement("both-kinds");

        CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v1", "ACTIVE");
        CompetenceFixtures.insertContentVersion(ds, requirementUuid, "TEST", "v1", "ACTIVE");

        // A requirement has two independently versioned artefacts (§2.2); a key of
        // requirement_uuid alone would have made that impossible.
        assertEquals(1L, activeCount(requirementUuid, "COURSE"));
        assertEquals(1L, activeCount(requirementUuid, "TEST"));
    }

    @Test
    @DisplayName("Many ARCHIVED rows coexist — their key is NULL and unique indexes ignore NULLs")
    void manyArchivedRowsCoexist() throws SQLException {
        String requirementUuid = requirement("archive-history");

        for (int i = 1; i <= 5; i++) {
            CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v" + i, "ARCHIVED");
        }

        List<Map<String, Object>> archived = rows(ds,
                "SELECT * FROM competence_content_version WHERE requirement_uuid = ? AND status = 'ARCHIVED'",
                requirementUuid);
        assertEquals(5, archived.size(), "Version history is unbounded by design");
        for (Map<String, Object> version : archived) {
            assertNull(version.get("active_key"), "An ARCHIVED row holds neither slot");
            assertNull(version.get("draft_key"));
        }
        // And the two singleton slots are still free.
        CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v6", "ACTIVE");
        CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v7", "DRAFT");
        assertEquals(1L, activeCount(requirementUuid, "COURSE"));
    }

    @Test
    @DisplayName("Flipping a row ACTIVE -> ARCHIVED frees the key for the next version")
    void archivingFreesTheActiveKey() throws SQLException {
        String requirementUuid = requirement("archive-frees");
        String outgoingUuid =
                CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v1", "ACTIVE");

        // The update trigger recomputes the key on every UPDATE, not only on INSERT — so the
        // status change alone releases the slot. This is the mechanism the publish transaction
        // depends on.
        assertEquals(1, update(ds,
                "UPDATE competence_content_version SET status = 'ARCHIVED' WHERE uuid = ?", outgoingUuid));
        assertNull(version(outgoingUuid).get("active_key"));

        String incomingUuid =
                CompetenceFixtures.insertContentVersion(ds, requirementUuid, "COURSE", "v2", "ACTIVE");

        assertEquals(requirementUuid + ":COURSE", version(incomingUuid).get("active_key"));
        assertEquals(1L, activeCount(requirementUuid, "COURSE"));
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private String requirement(String label) throws SQLException {
        String uuid = CompetenceFixtures.insertRequirement(ds, label);
        requirementUuids.add(uuid);
        return uuid;
    }

    private Map<String, Object> version(String uuid) throws SQLException {
        Map<String, Object> version =
                row(ds, "SELECT * FROM competence_content_version WHERE uuid = ?", uuid);
        assertNotNull(version, "Fixture row " + uuid + " is missing");
        return version;
    }

    private long activeCount(String requirementUuid, String kind) throws SQLException {
        return statusCount(requirementUuid, kind, "ACTIVE");
    }

    private long statusCount(String requirementUuid, String kind, String status) throws SQLException {
        return count(ds, """
                        SELECT COUNT(*) FROM competence_content_version
                         WHERE requirement_uuid = ? AND content_kind = ? AND status = ?""",
                requirementUuid, kind, status);
    }

    /**
     * A duplicate key, and specifically the named index.
     *
     * <p>SQLSTATE and vendor code identify the failure mode without depending on wording; the
     * index name is checked on top so a collision on the ACTIVE slot cannot be mistaken for
     * one on the DRAFT slot, which is the only way these two tests differ.
     */
    private void assertDuplicateKey(SQLException refusal, String expectedIndex) {
        assertEquals(INTEGRITY_CONSTRAINT, sqlStateOf(refusal),
                "Expected a unique-index violation, got: " + refusal.getMessage());
        assertEquals(DUPLICATE_ENTRY, refusal.getErrorCode(),
                "Expected MariaDB 1062 (duplicate entry), got: " + refusal.getMessage());
        String message = String.valueOf(refusal.getMessage()).toLowerCase(Locale.ROOT);
        assertTrue(message.contains(expectedIndex),
                "Expected the collision to name " + expectedIndex + ", got: " + refusal.getMessage());
    }
}

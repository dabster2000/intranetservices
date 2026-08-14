package dk.trustworks.intranet.competenceservice.persistence;

import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.competenceservice.model.ContentStatus;
import dk.trustworks.intranet.competenceservice.services.CompetenceContentService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.asDateTime;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.count;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.expectRefused;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.row;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.rows;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.sqlStateOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish transaction against the real schema (spec §5.3, §6.3, §19.1, §12.2).
 *
 * <p><strong>Why this tier.</strong> {@code CompetenceContentService.publish} archives the
 * outgoing ACTIVE, flushes, and only then activates the DRAFT. Written down that is an
 * ordinary sequence of two setters; the reason it is written that way is a MariaDB unique
 * index on a trigger-written column, and a DB-free test cannot see it. Worse, the failure mode
 * of getting it wrong is <em>intermittent</em>: without the explicit {@link
 * jakarta.persistence.EntityManager#flush()} Hibernate is free to order the two UPDATEs
 * however it likes at commit, so a mocked test would pass every time and production would fail
 * some of the time.
 *
 * <p>{@link #activatingBeforeArchivingCollides()} therefore issues the two writes in the wrong
 * order deliberately. It is the only test here that asserts a failure, and it exists so that
 * anyone tempted to simplify {@code publish()} into two setters and one commit finds out why
 * that does not work from a test rather than from an employee looking at the wrong course.
 *
 * <p>The force-retake tests cover the other half of publish: whether the audience keeps its
 * completions. Both directions are asserted, because "carry forward" and "everyone retakes"
 * are two different products and the flag is a boolean an author flips in a dialog.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompetencePublishTransactionTest {

    private static final String INTEGRITY_CONSTRAINT = "23000";
    private static final int DUPLICATE_ENTRY = 1062;

    @Inject
    DataSource ds;

    @Inject
    CompetenceContentService contentService;

    private final List<String> requirementUuids = new ArrayList<>();

    @AfterAll
    void dropFixtures() {
        // Requirements whose completions were carried forward stay behind: the completion
        // table refuses DELETE, and its FK then pins the content versions and the requirement.
        requirementUuids.forEach(uuid -> CompetenceFixtures.quietlyDropFixture(ds, uuid));
    }

    // =======================================================================
    // §19.1 — the order is not arbitrary
    // =======================================================================

    @Test
    @DisplayName("publish() archives the outgoing ACTIVE before activating the draft")
    void publishArchivesThenActivates() throws SQLException {
        String requirementUuid = requirement("publish-order");
        String outgoingUuid = version(requirementUuid, "COURSE", "v1", "ACTIVE");
        String draftUuid = version(requirementUuid, "COURSE", "v2", "DRAFT");

        CompetenceContentVersion published =
                contentService.publish(requirementUuid, ContentKind.COURSE, true, false, actor());

        assertEquals(draftUuid, published.getUuid());
        assertEquals(ContentStatus.ACTIVE, published.getStatus());

        Map<String, Object> outgoing = version(outgoingUuid);
        assertEquals("ARCHIVED", outgoing.get("status"));
        assertEquals(draftUuid, outgoing.get("superseded_by_uuid"),
                "The archived row has to point at what replaced it, or the history is a list of "
                        + "versions with no succession");
        assertNull(outgoing.get("active_key"), "Archiving is what releases the slot");

        Map<String, Object> incoming = version(draftUuid);
        assertEquals("ACTIVE", incoming.get("status"));
        assertEquals(requirementUuid + ":COURSE", incoming.get("active_key"));
        assertNull(incoming.get("draft_key"), "A published version is no longer a draft");
        assertNotNull(incoming.get("published_at"));
        assertNotNull(incoming.get("published_by"));

        assertEquals(1L, statusCount(requirementUuid, "COURSE", "ACTIVE"));
        assertEquals(0L, statusCount(requirementUuid, "COURSE", "DRAFT"));
    }

    @Test
    @DisplayName("Activating the draft before archiving the outgoing ACTIVE collides — this is why the order is fixed")
    void activatingBeforeArchivingCollides() throws SQLException {
        String requirementUuid = requirement("wrong-order");
        String outgoingUuid = version(requirementUuid, "COURSE", "v1", "ACTIVE");
        String draftUuid = version(requirementUuid, "COURSE", "v2", "DRAFT");

        // --- the wrong order: activate first, while the outgoing row still holds the key ---
        SQLException refusal = expectRefused(ds,
                "UPDATE competence_content_version SET status = 'ACTIVE' WHERE uuid = ?", draftUuid);

        assertEquals(INTEGRITY_CONSTRAINT, sqlStateOf(refusal),
                "Expected uk_competence_content_active to reject the second ACTIVE row, got: "
                        + refusal.getMessage());
        assertEquals(DUPLICATE_ENTRY, refusal.getErrorCode());
        assertTrue(String.valueOf(refusal.getMessage()).contains("uk_competence_content_active"),
                "Expected the active-slot index to be named, got: " + refusal.getMessage());

        // Nothing moved: the draft is still a draft and the old version is still live.
        assertEquals("DRAFT", version(draftUuid).get("status"));
        assertEquals("ACTIVE", version(outgoingUuid).get("status"));

        // --- the right order: archive, then activate ---
        assertEquals(1, CompetenceFixtures.update(ds, """
                        UPDATE competence_content_version
                           SET status = 'ARCHIVED', superseded_by_uuid = ?
                         WHERE uuid = ?""",
                draftUuid, outgoingUuid));
        assertEquals(1, CompetenceFixtures.update(ds,
                "UPDATE competence_content_version SET status = 'ACTIVE' WHERE uuid = ?", draftUuid));

        assertEquals(requirementUuid + ":COURSE", version(draftUuid).get("active_key"));
        assertEquals(1L, statusCount(requirementUuid, "COURSE", "ACTIVE"));
    }

    // =======================================================================
    // §5.3 — force retake
    // =======================================================================

    @Test
    @DisplayName("Force-retake OFF appends carry-forward completions with the original completed_at")
    void forceRetakeOffCarriesCompletionsForward() throws SQLException {
        String requirementUuid = requirement("carry-forward");
        String outgoingUuid = version(requirementUuid, "COURSE", "v1", "ACTIVE");
        String draftUuid = version(requirementUuid, "COURSE", "v2", "DRAFT");

        String userA = UUID.randomUUID().toString();
        String userB = UUID.randomUUID().toString();
        LocalDateTime older = at(200);
        LocalDateTime newer = at(100);
        LocalDateTime otherUser = at(150);
        CompetenceFixtures.insertCompletion(ds, userA, requirementUuid, outgoingUuid, "v1", older);
        CompetenceFixtures.insertCompletion(ds, userA, requirementUuid, outgoingUuid, "v1", newer);
        CompetenceFixtures.insertCompletion(ds, userB, requirementUuid, outgoingUuid, "v1", otherUser);

        contentService.publish(requirementUuid, ContentKind.COURSE, false, false, actor());

        List<Map<String, Object>> carried = rows(ds, """
                        SELECT * FROM competence_course_completion
                         WHERE content_version_uuid = ? ORDER BY completed_at""",
                draftUuid);
        assertEquals(2, carried.size(),
                "One carry-forward row per person, not per historical completion");

        Map<String, Object> carriedForA = completionFor(carried, userA);
        assertEquals("v2", carriedForA.get("version_label"));
        assertEquals(requirementUuid, carriedForA.get("requirement_uuid"));
        // Cadence is evaluated at read time against completed_at (§5.8). Stamping the publish
        // time here would silently hand the whole audience a fresh year of validity because
        // somebody fixed a typo — so the ORIGINAL timestamp is copied, and for a user with two
        // completions it is the newest one that carries.
        assertEquals(newer, asDateTime(carriedForA.get("completed_at")));
        assertEquals(otherUser, asDateTime(completionFor(carried, userB).get("completed_at")));

        // The originals are evidence that these people read *that* version on that day, and
        // are untouched — the table refuses UPDATE, so carry-forward can only append.
        assertEquals(3L, count(ds,
                "SELECT COUNT(*) FROM competence_course_completion WHERE content_version_uuid = ?",
                outgoingUuid));
        assertEquals(5L, count(ds,
                "SELECT COUNT(*) FROM competence_course_completion WHERE requirement_uuid = ?",
                requirementUuid));

        // Force-retake OFF is a correction, and the published row records that.
        assertEquals(0, CompetenceFixtures.asInt(version(draftUuid).get("forced_retake")));
    }

    @Test
    @DisplayName("Force-retake ON appends nothing — the new version label is what makes the audience retake")
    void forceRetakeOnCarriesNothingForward() throws SQLException {
        String requirementUuid = requirement("no-carry-forward");
        String outgoingUuid = version(requirementUuid, "COURSE", "v1", "ACTIVE");
        String draftUuid = version(requirementUuid, "COURSE", "v2", "DRAFT");

        String user = UUID.randomUUID().toString();
        CompetenceFixtures.insertCompletion(ds, user, requirementUuid, outgoingUuid, "v1", at(30));

        contentService.publish(requirementUuid, ContentKind.COURSE, true, false, actor());

        assertEquals(0L, count(ds,
                "SELECT COUNT(*) FROM competence_course_completion WHERE content_version_uuid = ?",
                draftUuid),
                "Nothing is appended: §5.3 compares the latest completion's version_label to the "
                        + "active one, so the new label flips the audience to 'retake required' on its own");
        assertEquals(1L, count(ds,
                "SELECT COUNT(*) FROM competence_course_completion WHERE requirement_uuid = ?",
                requirementUuid));
        assertEquals(1, CompetenceFixtures.asInt(version(draftUuid).get("forced_retake")));
    }

    @Test
    @DisplayName("Force-retake OFF has no effect on a TEST — attempts are immutable, so there is nothing to carry")
    void forceRetakeOffOnATestCarriesNothing() throws SQLException {
        String requirementUuid = requirement("test-kind");
        version(requirementUuid, "TEST", "v1", "ACTIVE");
        String draftUuid = version(requirementUuid, "TEST", "v2", "DRAFT");

        // Publishing a TEST with force-retake off is accepted but does nothing extra: §5.4
        // compares the label on the passed attempt, and the attempt trigger permits only the
        // scoring write, the abandon flag and pseudonymisation. The publish dialog has to say
        // so out loud, which is only defensible if the backend really behaves this way.
        contentService.publish(requirementUuid, ContentKind.TEST, false, false, actor());

        assertEquals("ACTIVE", version(draftUuid).get("status"));
        assertEquals(0L, count(ds,
                "SELECT COUNT(*) FROM competence_course_completion WHERE requirement_uuid = ?",
                requirementUuid));
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private String requirement(String label) throws SQLException {
        String uuid = CompetenceFixtures.insertRequirement(ds, label);
        requirementUuids.add(uuid);
        return uuid;
    }

    private String version(String requirementUuid, String kind, String label, String status)
            throws SQLException {
        return CompetenceFixtures.insertContentVersion(ds, requirementUuid, kind, label, status);
    }

    private Map<String, Object> version(String uuid) throws SQLException {
        Map<String, Object> version =
                row(ds, "SELECT * FROM competence_content_version WHERE uuid = ?", uuid);
        assertNotNull(version, "Fixture version " + uuid + " is missing");
        return version;
    }

    private long statusCount(String requirementUuid, String kind, String status) throws SQLException {
        return count(ds, """
                        SELECT COUNT(*) FROM competence_content_version
                         WHERE requirement_uuid = ? AND content_kind = ? AND status = ?""",
                requirementUuid, kind, status);
    }

    private Map<String, Object> completionFor(List<Map<String, Object>> completions, String useruuid) {
        return completions.stream()
                .filter(row -> useruuid.equals(row.get("useruuid")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No carry-forward row for " + useruuid));
    }

    /** Whole seconds: DATETIME(6) round-trips them exactly, so equality is safe to assert. */
    private LocalDateTime at(int daysAgo) {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(daysAgo);
    }

    private String actor() {
        return UUID.randomUUID().toString();
    }
}

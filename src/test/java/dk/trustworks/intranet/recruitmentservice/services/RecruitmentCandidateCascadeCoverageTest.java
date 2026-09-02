package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completeness ratchet for the candidate hard-delete cascade.
 *
 * <p>Every foreign key into {@code recruitment_candidates} is
 * {@code ON DELETE RESTRICT} except one, so a table that acquires such a key
 * and is not handled by
 * {@link RecruitmentCandidateDeleteCascade} does not degrade the feature —
 * it makes the delete throw a constraint violation in production, after the
 * external Slack and Graph redaction has already run and cannot be undone.
 * Nothing else in CI notices: the recruitment contract tests are
 * hand-enumerated allowlists over method names, and the cascade has no
 * DB-free behavioural test because it is nothing but Panache statics.</p>
 *
 * <p>So this test re-derives the FK set from the migration files themselves —
 * the same source of truth the database uses — and fails the build when it
 * stops matching the set the cascade declares. A new migration adding a ninth
 * candidate FK breaks here, at the point where someone can still do something
 * about it.</p>
 */
class RecruitmentCandidateCascadeCoverageTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path CASCADE_SOURCE = Path.of(
            "src/main/java/dk/trustworks/intranet/recruitmentservice/services",
            "RecruitmentCandidateDeleteCascade.java");

    /** {@code CREATE TABLE [IF NOT EXISTS] x} / {@code ALTER TABLE x} — the current subject. */
    private static final Pattern TABLE_SUBJECT = Pattern.compile(
            "^\\s*(?:CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?|ALTER\\s+TABLE)\\s+`?([a-z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REFERENCES_CANDIDATES = Pattern.compile(
            "REFERENCES\\s+recruitment_candidates\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void theCascadeHandlesEveryTableWithAForeignKeyOntoCandidates() throws IOException {
        Set<String> fromMigrations = tablesWithCandidateForeignKey();

        assertFalse(fromMigrations.isEmpty(),
                "no candidate foreign keys found — the migration scan itself is broken, which "
                        + "would make this whole ratchet silently vacuous");
        assertEquals(new TreeSet<>(RecruitmentCandidateDeleteCascade.DIRECT_CANDIDATE_FK_TABLES),
                fromMigrations,
                "RecruitmentCandidateDeleteCascade.DIRECT_CANDIDATE_FK_TABLES has drifted from "
                        + "the migrations. A table that gained a candidate FK must be handled by "
                        + "the cascade (deleted, or unlinked like recruitment_referrals) and "
                        + "listed there; every one of these FKs is ON DELETE RESTRICT bar "
                        + "recruitment_discussion_threads, so an unhandled one is a production "
                        + "constraint violation mid-delete, not a degraded feature.");
    }

    @Test
    void theCascadeSourceActuallyMentionsEveryOneOfThem() throws IOException {
        String source = Files.readString(CASCADE_SOURCE, StandardCharsets.UTF_8);

        for (String table : RecruitmentCandidateDeleteCascade.DIRECT_CANDIDATE_FK_TABLES) {
            assertTrue(source.contains("counts.put(\"" + table),
                    "the cascade declares it handles " + table + " but never records a count "
                            + "for it — the declaration must not be decoration. Deleted or "
                            + "unlinked, every candidate FK table has to show up in the ledger's "
                            + "per-table counts.");
        }
    }

    /**
     * The transitive chains have no candidate column, so the FK scan above
     * cannot see them — but they RESTRICT just as hard, one level further out.
     * Pinned by name because forgetting one is the same production failure.
     */
    @Test
    void theCascadeAlsoHandlesTheTransitiveChains() throws IOException {
        String source = Files.readString(CASCADE_SOURCE, StandardCharsets.UTF_8);

        List<String> transitive = List.of(
                "recruitment_scorecards",            // -> recruitment_interviews (V442)
                "recruitment_interviews",            // -> recruitment_applications (V442)
                "recruitment_calendar_hold",         // -> recruitment_proposed_slot (V498)
                "recruitment_slot_approval",         // -> recruitment_proposed_slot (V498)
                "recruitment_proposed_slot",         // -> recruitment_scheduling_request (V498)
                "recruitment_option_batch",          // -> recruitment_scheduling_request (V498)
                "recruitment_scheduling_outbox",     // -> recruitment_scheduling_request (V498)
                "recruitment_availability_constraint", // -> ..._evidence (V500)
                "recruitment_availability_evidence", // -> recruitment_scheduling_request (V500)
                "recruitment_scheduling_request",    // -> recruitment_applications (V498)
                "candidate_dossier_appendices",      // -> candidate_dossiers (V314)
                "candidate_dossier_revisions");      // -> candidate_dossiers (V313)

        for (String table : transitive) {
            assertTrue(source.contains("counts.put(\"" + table + "\""),
                    table + " RESTRICTs its parent and must be deleted before it; "
                            + "the cascade records no count for it");
        }
    }

    /**
     * The soft references: no constraint stops the delete, and nothing cleans
     * them up either — they simply rot, pointing at a candidate that no longer
     * exists. Each one is a deliberate decision recorded in the cascade.
     */
    @Test
    void theCascadeAlsoHandlesTheSoftReferences() throws IOException {
        String source = Files.readString(CASCADE_SOURCE, StandardCharsets.UTF_8);

        List<String> soft = List.of(
                "recruitment_events",                 // V433 — soft by module convention
                "recruitment_reactor_deliveries",     // V433
                "recruitment_reactor_dead_letters",   // V490
                "recruitment_slack_threads",          // V451, keyed by application
                "recruitment_airtable_records",       // V483 — unlinked, row kept
                "onboarding_upload_tokens",           // V322
                "onboarding_upload_submissions",      // V327
                // V561. Keyed by token_uuid, not candidate_uuid, so it has to
                // be resolved through the tokens BEFORE they are deleted —
                // hence its position above the other two in the cascade.
                "onboarding_upload_attempts");        // V561

        for (String table : soft) {
            assertTrue(source.contains("counts.put(\"" + table),
                    table + " carries a candidate reference with no DB constraint — nothing "
                            + "would fail if the cascade forgot it, which is exactly why it is "
                            + "pinned here");
        }
    }

    // ---- Migration scan ---------------------------------------------------------

    private static Set<String> tablesWithCandidateForeignKey() throws IOException {
        Set<String> tables = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String subject = null;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String withoutComment = stripComment(line);
                    Matcher subjectMatch = TABLE_SUBJECT.matcher(withoutComment);
                    if (subjectMatch.find()) {
                        subject = subjectMatch.group(1).toLowerCase(Locale.ROOT);
                    }
                    if (subject != null
                            && !subject.equals("recruitment_candidates")
                            && REFERENCES_CANDIDATES.matcher(withoutComment).find()) {
                        tables.add(subject);
                    }
                }
            }
        }
        return tables;
    }

    /** Line comments would otherwise turn prose about a FK into a phantom FK. */
    private static String stripComment(String line) {
        int marker = line.indexOf("--");
        return marker < 0 ? line : line.substring(0, marker);
    }
}

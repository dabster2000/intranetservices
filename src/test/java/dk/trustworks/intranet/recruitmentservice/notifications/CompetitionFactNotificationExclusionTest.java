package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §7.2 notification exclusion, as a test rather than a convention
 * (room spec 2026-08-26): competition facts — who else is courting the
 * candidate, what decides it, when they must answer — are excluded from
 * EVERY Slack notification path, not merely gated inside one. The
 * candidate boundary is a bounded set of people who went looking; a
 * channel post is a broadcast that cannot be walked back.
 * <p>
 * Two layers:
 * <ol>
 *   <li>No notification/Slack builder may reference a competition fact
 *       key at all — enumerated over both packages' sources, so a future
 *       event type cannot quietly inherit a broadcast.</li>
 *   <li>The notes write path must skip the discussion notifier for EVERY
 *       structured fact note (the field marker is the guard, so no future
 *       vocabulary entry starts broadcasting by default).</li>
 * </ol>
 */
class CompetitionFactNotificationExclusionTest {

    private static final List<Path> NOTIFICATION_SOURCES = List.of(
            Path.of("src/main/java/dk/trustworks/intranet/recruitmentservice/notifications"),
            Path.of("src/main/java/dk/trustworks/intranet/recruitmentservice/slack"));

    @Test
    void noNotificationBuilder_referencesACompetitionFact() throws IOException {
        List<String> competitionKeys = RecruitmentFactVocabulary.all().stream()
                .filter(f -> RecruitmentFactVocabulary.isCompetitionScoped(f.key()))
                .map(RecruitmentFactVocabulary.FactField::key)
                .toList();
        assertTrue(!competitionKeys.isEmpty(), "the competition group must exist");
        for (Path packageDir : NOTIFICATION_SOURCES) {
            try (Stream<Path> files = Files.walk(packageDir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    for (String key : competitionKeys) {
                        assertTrue(!source.contains(key),
                                file.getFileName() + " references competition fact " + key
                                        + " — competition facts never reach Slack (spec §7.2)");
                    }
                }
            }
        }
    }

    @Test
    void factNotes_neverTriggerTheDiscussionNotifier() throws IOException {
        String resource = Files.readString(Path.of(
                "src/main/java/dk/trustworks/intranet/recruitmentservice/resources/RecruitmentResource.java"));
        assertTrue(resource.contains("request.field() == null)")
                        && resource.contains("discussionSlackNotifier.onNoteAdded"),
                "RecruitmentResource.addNote must call the discussion notifier only for "
                        + "plain notes (field == null) — a structured fact is data, not a "
                        + "discussion, and must never produce a channel post (spec §7.2)");
    }
}

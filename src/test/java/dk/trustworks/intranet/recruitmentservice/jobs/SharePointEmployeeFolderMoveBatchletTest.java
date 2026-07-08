package dk.trustworks.intranet.recruitmentservice.jobs;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the WARN-throttling policy in
 * {@link SharePointEmployeeFolderMoveBatchlet}. The retry poll runs every
 * ~5 minutes; before this policy a HIRED candidate whose converted-user
 * username can never be resolved (missing user row / null username) produced
 * one WARN on every run — 288/day in prod for a single stuck candidate,
 * drowning real signal. {@code newlyUnresolved} is the pure decision function
 * that keeps steady-state noise at zero while still surfacing a genuinely new
 * data defect exactly once. Panache static finders can't be mocked in a plain
 * unit test, so these tests target the pure logic only.
 */
class SharePointEmployeeFolderMoveBatchletTest {

    private static final String STUCK = "e504b2fd-be78-4bcf-9b92-df4c63075837";

    @Test
    void firstSighting_isReportedOnce() {
        List<String> newly = SharePointEmployeeFolderMoveBatchlet
                .newlyUnresolved(Set.of(), Set.of(STUCK));

        assertEquals(List.of(STUCK), newly,
                "A newly-unresolvable candidate must be WARNed once");
    }

    @Test
    void alreadyWarnedCandidate_isSuppressedOnSubsequentRuns() {
        List<String> newly = SharePointEmployeeFolderMoveBatchlet
                .newlyUnresolved(Set.of(STUCK), Set.of(STUCK));

        assertTrue(newly.isEmpty(),
                "The same stuck candidate must not re-WARN on every ~5-minute run");
    }

    @Test
    void onlyTheNewCandidate_isReported_whenTheSetGrows() {
        String fresh = "11111111-2222-3333-4444-555555555555";

        List<String> newly = SharePointEmployeeFolderMoveBatchlet
                .newlyUnresolved(Set.of(STUCK), Set.of(STUCK, fresh));

        assertEquals(List.of(fresh), newly,
                "Only the newly-unresolvable candidate should be WARNed, not the known one");
    }

    @Test
    void noUnresolvedCandidates_producesNothing() {
        List<String> newly = SharePointEmployeeFolderMoveBatchlet
                .newlyUnresolved(Set.of(STUCK), Set.of());

        assertTrue(newly.isEmpty(), "An empty run must not WARN");
    }

    @Test
    void output_isSortedForStableLogLines_andInputsAreNotMutated() {
        Set<String> previouslyWarned = new HashSet<>();
        Set<String> thisRun = new LinkedHashSet<>(List.of("ccc", "aaa", "bbb"));

        List<String> newly = SharePointEmployeeFolderMoveBatchlet
                .newlyUnresolved(previouslyWarned, thisRun);

        assertEquals(List.of("aaa", "bbb", "ccc"), newly, "output must be sorted");
        assertTrue(previouslyWarned.isEmpty(), "previouslyWarned must not be mutated");
        assertEquals(3, thisRun.size(), "thisRun must not be mutated");
    }
}

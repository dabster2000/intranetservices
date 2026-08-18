package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.DedupeMatch;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A4: {@code POST /recruitment/candidates/dedupe-check} must not be an
 * identity oracle.
 *
 * <p>The check takes an arbitrary email or LinkedIn URL and answers with a
 * candidate uuid and a full name. Unfiltered, that is enough to learn that a
 * named executive is a candidate on a confidential partner req — and, before
 * the attach path grew its own candidate gate, enough to de-cloak them.
 * Change A widens this route from the recruiter tier to every intake-grant
 * holder, so the filter has to be in place first.
 *
 * <p>{@code putCandidate} is the single funnel every candidate match passes
 * through — both the email leg and the LinkedIn leg call it — so pinning the
 * filter there pins it for the whole endpoint, without a database.
 */
class CandidateDedupeVisibilityFilterTest {

    private static final String VIEWER = UUID.randomUUID().toString();

    private RecruitmentVisibility visibility;
    private CandidateDedupeService dedupeService;
    private RecruitmentCandidate candidate;
    private Map<String, DedupeMatch> matches;

    @BeforeEach
    void setUp() {
        visibility = mock(RecruitmentVisibility.class);
        dedupeService = new CandidateDedupeService();
        dedupeService.visibility = visibility;

        candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());
        candidate.setFirstName("Confidential");
        candidate.setLastName("Executive");

        matches = new LinkedHashMap<>();
    }

    @Test
    void aCandidateTheViewerMayNotRead_isNotReturned() {
        when(visibility.canReadCandidateProfile(VIEWER, candidate)).thenReturn(false);

        dedupeService.putCandidate(matches, candidate, DedupeMatch.MatchedOn.EMAIL, VIEWER, true);

        assertTrue(matches.isEmpty(),
                "a partner-track or hired candidate must not surface through the dedupe check");
    }

    @Test
    void aCandidateTheViewerMayRead_isReturnedUnchanged() {
        when(visibility.canReadCandidateProfile(VIEWER, candidate)).thenReturn(true);

        dedupeService.putCandidate(matches, candidate, DedupeMatch.MatchedOn.EMAIL, VIEWER, true);

        assertEquals(1, matches.size(), "the response shape is unchanged for permitted matches");
        DedupeMatch match = matches.values().iterator().next();
        assertEquals(DedupeMatch.MatchType.CANDIDATE, match.type());
        assertEquals(candidate.getUuid(), match.uuid());
        assertEquals("Confidential Executive", match.name());
        assertEquals(DedupeMatch.MatchedOn.EMAIL, match.matchedOn());
    }

    @Test
    void theLinkedInLegIsFilteredToo_notJustEmail() {
        when(visibility.canReadCandidateProfile(VIEWER, candidate)).thenReturn(false);

        dedupeService.putCandidate(matches, candidate, DedupeMatch.MatchedOn.LINKEDIN, VIEWER, true);

        assertTrue(matches.isEmpty(),
                "probing by LinkedIn slug must not be a way around the filter");
    }

    @Test
    void aBlankViewerSeesNothing_failClosed() {
        // canReadCandidateProfile already answers false for a blank viewer;
        // this pins that the filter is asked at all rather than short-circuited.
        when(visibility.canReadCandidateProfile(null, candidate)).thenReturn(false);

        dedupeService.putCandidate(matches, candidate, DedupeMatch.MatchedOn.EMAIL, null, true);

        assertTrue(matches.isEmpty());
    }

    @Test
    void theSystemReusePath_isDeliberatelyUnfiltered() {
        // The public /apply reuse decision has no viewer. Filtering it would
        // make every returning applicant mint a duplicate candidate row.
        dedupeService.putCandidate(matches, candidate, DedupeMatch.MatchedOn.EMAIL, null, false);

        assertEquals(1, matches.size());
    }

    @Test
    void theUnfilteredEntryPoint_isNotReachableFromAResource() {
        // The one structural guarantee that keeps the two entry points from
        // being confused: checkForSystemReuse is package-private, so nothing
        // in ...recruitmentservice.resources can call it. Making it public
        // would re-open the leak with a one-word diff.
        Method systemReuse = requireMethod("checkForSystemReuse");
        assertFalse(Modifier.isPublic(systemReuse.getModifiers()),
                "checkForSystemReuse must stay package-private — only the public /apply "
                        + "service in this package may use the unfiltered check");

        Method viewerAware = requireMethod("check");
        assertTrue(Modifier.isPublic(viewerAware.getModifiers()));
        assertEquals(3, viewerAware.getParameterCount(),
                "the public check must take a viewer — a 2-arg overload would silently "
                        + "restore the unfiltered behaviour at every call site");
    }

    private static Method requireMethod(String name) {
        for (Method m : CandidateDedupeService.class.getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        throw new AssertionError("CandidateDedupeService must declare " + name + "(...)");
    }
}

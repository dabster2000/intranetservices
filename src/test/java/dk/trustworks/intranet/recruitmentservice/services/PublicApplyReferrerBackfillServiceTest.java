package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DB-free tests for the one-off referrer backfill's pure half — reading a
 * name back out of the {@code source_detail} blob.
 * <p>
 * This is the part that decides whether a real candidate's referrer is
 * recovered or silently skipped, and the blob is untyped JSON written by
 * several generations of the public form, so the parsing is where the bugs
 * would live. The reported production row is the first fixture verbatim.
 */
class PublicApplyReferrerBackfillServiceTest {

    /** The reported row: source WEBSITE, the referrer only inside the blob. */
    private static Map<String, Object> henriksBlob() {
        Map<String, Object> blob = new HashMap<>();
        blob.put("selfReportedSource", "NETWORK");
        blob.put("referenceName", "Simon Brandt Sørensen");
        blob.put("desiredPracticeUuid", "139781f7-84c2-11f1-9503-027533d3d1d3");
        blob.put("desiredPracticeName", "Business & Users");
        return blob;
    }

    @Test
    void readsTheNameOutOfARealProductionBlob() {
        assertEquals("Simon Brandt Sørensen",
                PublicApplyReferrerBackfillService.claimedNameOf(henriksBlob()),
                "this is the whole point: the name was there all along");
    }

    @Test
    void aBlobWithoutAReferenceNameYieldsNothing() {
        Map<String, Object> blob = new HashMap<>();
        blob.put("selfReportedSource", "JOBINDEX");
        assertNull(PublicApplyReferrerBackfillService.claimedNameOf(blob));
    }

    @Test
    void nullAndEmptyAreAbsent_notAMatchAttempt() {
        assertNull(PublicApplyReferrerBackfillService.claimedNameOf(null));
        assertNull(PublicApplyReferrerBackfillService.claimedNameOf(Map.of()));
        Map<String, Object> blank = new HashMap<>();
        blank.put("referenceName", "   ");
        assertNull(PublicApplyReferrerBackfillService.claimedNameOf(blank),
                "whitespace is not a colleague");
    }

    @Test
    void aNonStringValueIsIgnoredRatherThanStringified() {
        // Older form versions and hand-repaired rows are why this is defensive:
        // "[object Object]" reaching the matcher would be a name that matches
        // nothing and reads like a bug report waiting to happen.
        Map<String, Object> weird = new HashMap<>();
        weird.put("referenceName", List.of("Simon"));
        assertNull(PublicApplyReferrerBackfillService.claimedNameOf(weird));

        Map<String, Object> numeric = new HashMap<>();
        numeric.put("referenceName", 42);
        assertNull(PublicApplyReferrerBackfillService.claimedNameOf(numeric));
    }

    @Test
    void surroundingWhitespaceIsTrimmedSoTheMatcherSeesACleanName() {
        Map<String, Object> padded = new HashMap<>();
        padded.put("referenceName", "  Simon Brandt Sørensen \n");
        assertEquals("Simon Brandt Sørensen",
                PublicApplyReferrerBackfillService.claimedNameOf(padded));
    }

    @Test
    void theKeyIsTheOneTheFormActuallyWrites() {
        // If this constant ever drifts from the form's key the backfill goes
        // quietly zero-match, which looks exactly like "nothing to do".
        assertEquals("referenceName", PublicApplyReferrerBackfillService.REFERENCE_NAME_KEY);
    }

    // ---- The multi-name case the AI leg exists for ----------------------
    // A real production answer named three colleagues in one field. The
    // deterministic tiers read it as one name and match nothing; the AI leg
    // splits it. What must NOT happen is linking to whichever one comes back
    // first — referred_by_user_uuid holds one person, and picking arbitrarily
    // asserts a relationship the applicant never singled out.

    private static final List<AirtableReferrerMatcher.DirectoryUser> DIRECTORY = List.of(
            new AirtableReferrerMatcher.DirectoryUser("u-kasper", "Kasper Thorhauge", "Grønbæk"),
            new AirtableReferrerMatcher.DirectoryUser("u-simon", "Simon Brandt", "Sørensen"),
            new AirtableReferrerMatcher.DirectoryUser("u-mia", "Mia", "Jørgensen"));

    @Test
    void theWholeMultiNameStringMatchesNobodyDeterministically() {
        assertNull(AirtableReferrerMatcher.deterministicMatch(
                        "Kasper Thorhauge Grønbæk, Simon Branddt Sørensen, Mia Jørgensen",
                        DIRECTORY),
                "read as one name it is nobody — which is why the sweep missed it");
    }

    @Test
    void eachExtractedNameOnItsOwnDoesMatch() {
        assertEquals("u-kasper", AirtableReferrerMatcher.deterministicMatch(
                "Kasper Thorhauge Grønbæk", DIRECTORY));
        assertEquals("u-mia", AirtableReferrerMatcher.deterministicMatch(
                "Mia Jørgensen", DIRECTORY));
    }

    @Test
    void aMisspeltNameStillResolvesOnTheFirstAndLastToken() {
        // "Branddt" — the token match is first+last, so the mangled middle
        // name does not cost the link. This is why extraction helps here.
        assertEquals("u-simon", AirtableReferrerMatcher.deterministicMatch(
                "Simon Branddt Sørensen", DIRECTORY));
    }

    @Test
    void aSingleNameTypoInTheLastTokenIsStillNotRecoverable() {
        // Honest limit: the model returns names as written, so "Marquad" for
        // "Marquard" fails the token match too. The AI leg fixes multi-name
        // answers, NOT spelling.
        List<AirtableReferrerMatcher.DirectoryUser> dir = List.of(
                new AirtableReferrerMatcher.DirectoryUser("u-n", "Nicolaj", "Marquard"));
        assertNull(AirtableReferrerMatcher.deterministicMatch("Nicolaj Marquad", dir));
    }

    @Test
    void ambiguityIsStillNeverGuessed() {
        List<AirtableReferrerMatcher.DirectoryUser> twoLarses = List.of(
                new AirtableReferrerMatcher.DirectoryUser("u-a", "Lars", "Hansen"),
                new AirtableReferrerMatcher.DirectoryUser("u-b", "Lars", "Hansen"));
        assertNull(AirtableReferrerMatcher.deterministicMatch("Lars Hansen", twoLarses));
    }
}

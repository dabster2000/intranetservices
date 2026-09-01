package dk.trustworks.intranet.recruitmentservice.services;

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
}

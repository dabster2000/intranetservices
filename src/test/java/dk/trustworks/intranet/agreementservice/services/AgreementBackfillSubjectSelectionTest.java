package dk.trustworks.intranet.agreementservice.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who the backfill walk treats as a corpus subject.
 *
 * <p>On 2026-09-07 the answer was wrong in a way nothing could see. The walk
 * selected subjects with {@code findEmployedUsersByDate}, whose status list is
 * ACTIVE plus the leave states, so the six PREBOARDING employees were not
 * subjects. Their documents were in {@code employee_documents} — fourteen
 * signed contracts, tillæg and loyalty programmes between them — but the walk
 * filters the corpus down to its subjects, so those rows were discarded before
 * extraction. None of the six got a single agreement, and {@code GET
 * /agreements} returned an empty list for them, which reads exactly like an
 * employee who legitimately has none.</p>
 *
 * <p>The run report could not surface it either. It counts subjects with no
 * documents; a non-subject cannot be counted as uncovered. The 2026-09-07 run
 * recorded "143 aktive medarbejdere, 140/140 med dokumenter" and flagged three
 * uncovered employees — a clean bill of health while six people were out of
 * scope entirely.</p>
 *
 * <p><b>Why this test is structural.</b> {@code walk()} cannot run in the
 * DB-free tier — it opens {@code requiringNew} transactions, reads
 * {@code employee_documents} through Panache and streams bytes from S3. The
 * selector call is the whole defect, so the assertion is on the call.</p>
 */
class AgreementBackfillSubjectSelectionTest {

    private static final Path WALKER = Path.of(
            "src/main/java/dk/trustworks/intranet/agreementservice/services/AgreementBackfillWalkerService.java");

    @Test
    void theWalkIncludesPreboardingEmployees() throws IOException {
        String code = code();

        assertTrue(code.contains("findEmployedOrPreboardingUsersByDate("),
                "the corpus must be selected with the preboarding-inclusive selector, or employees "
                        + "who have signed but not started are silently out of scope");
        assertFalse(code.contains("findEmployedUsersByDate("),
                "findEmployedUsersByDate excludes PREBOARDING — it is the finance/statistics selector, "
                        + "and using it here is the 2026-09-07 defect");
        assertFalse(code.contains("findWorkingUsersByDate("),
                "findWorkingUsersByDate is ACTIVE-only; it would drop the leave states too");
    }

    /**
     * The corpus filter and the uncovered-count must both be driven by the same
     * subject set the selector returned. A filter left on a narrower set would
     * reintroduce the bug behind a correct-looking selector call.
     */
    @Test
    void theCorpusAndTheUncoveredCountUseTheSelectedSubjects() throws IOException {
        String code = code();

        assertTrue(code.contains("subjectUuids.contains(doc.getUserUuid())"),
                "the document corpus must be filtered to the selected subjects");
        assertTrue(code.contains("uncovered = subjectUuids.stream()"),
                "the uncovered-employee note must count against the same subject set, otherwise the "
                        + "run reports completeness over a different population than it walked");
        assertFalse(code.contains("activeUuids"),
                "the subject set is no longer 'active' — a name that says otherwise is how the next "
                        + "reader concludes preboarders are out of scope by design");
    }

    /** Comments and string literals removed, so prose about the defect cannot satisfy or break the assertions. */
    private static String code() throws IOException {
        assertTrue(Files.exists(WALKER), "expected to find " + WALKER.toAbsolutePath()
                + " — this test reads the source, so it must run from the module root");
        String source = Files.readString(WALKER);

        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
            } else if (c == '"' && next == '"' && i + 2 < n && source.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(source.charAt(i) == '"'
                        && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"')) {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 3, n);
            } else if (c == '"') {
                i++;
                while (i < n && source.charAt(i) != '"' && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
